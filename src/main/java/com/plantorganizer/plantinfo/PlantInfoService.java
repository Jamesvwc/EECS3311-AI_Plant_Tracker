package com.plantorganizer.plantinfo;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import com.plantorganizer.common.ExternalServiceException;
import com.plantorganizer.identification.PlantCandidate;
import com.plantorganizer.websearch.WebResult;
import com.plantorganizer.websearch.WebSearch;

/**
 * Finds care information for a confirmed species (Stage 1 UC13 "Look Up Care
 * Info", steps 5-6a of the identification workflow).
 *
 * Decision logic:
 *   1. Ask the primary source (Perenual) with the scientific name.
 *   2. If that fails or finds nothing, retry once: with the common name when
 *      the species was not found (no retry without one), or with the same
 *      query after a temporary error. A failure that cannot change on retry
 *      (bad key, plan limit) skips the retry.
 *   3. After the second failure, ask the fallback source (Cohere fills the
 *      care template; marked lower reliability).
 * Successful lookups are cached in memory so repeated lookups do not spend
 * Perenual's small daily request quota.
 *
 * Both sources are only seen through {@link PlantInfoProvider}, so this class
 * does not know which API sits behind either one.
 */
@Service
public class PlantInfoService {

    private static final Logger log = LoggerFactory.getLogger(PlantInfoService.class);
    static final int MAX_PRIMARY_ATTEMPTS = 2;

    private final PlantInfoProvider primary;
    private final PlantInfoProvider fallback;
    private final WebSearch webSearch;
    private final Map<String, CareLookup> cache = new ConcurrentHashMap<>();
    private final Map<String, List<PlantCandidate>> searchCache = new ConcurrentHashMap<>();
    private final Map<String, Optional<PlantCandidate>> speciesCache = new ConcurrentHashMap<>();

    public PlantInfoService(@Qualifier("perenualAdapter") PlantInfoProvider primary,
                            @Qualifier("llmCareInfoAdapter") PlantInfoProvider fallback,
                            WebSearch webSearch) {
        this.primary = primary;
        this.fallback = fallback;
        this.webSearch = webSearch;
    }

    public CareLookup lookup(PlantQuery query) {
        String cacheKey = PlantQuery.binomial(query.scientificName()).toLowerCase(Locale.ROOT);
        CareLookup cached = cache.get(cacheKey);
        if (cached != null) {
            return cached;
        }

        List<String> notes = new ArrayList<>();
        Optional<PlantCareProfile> profile = tryPrimary(query, notes);
        if (profile.isEmpty()) {
            profile = tryFallback(query, notes);
        }
        CareLookup result = new CareLookup(profile.orElseThrow(), notes);
        cache.put(cacheKey, result);
        return result;
    }

    /**
     * Stage 1 searchByName(): which species in the plant database match this
     * name? Used to verify names proposed by the AI. Results are cached.
     *
     * @throws ExternalServiceException when the database cannot be searched
     */
    public List<PlantCandidate> searchByName(String name) {
        String key = name.trim().toLowerCase(Locale.ROOT);
        List<PlantCandidate> cached = searchCache.get(key);
        if (cached != null) {
            return cached;
        }
        List<PlantCandidate> found = List.copyOf(primary.searchByName(name.trim()));
        searchCache.put(key, found);
        return found;
    }

    /** The search hit whose genus + species equals the given name, if any. */
    public Optional<PlantCandidate> verifySpecies(String scientificName) {
        String wanted = PlantQuery.binomial(scientificName);
        return searchByName(scientificName).stream()
                .filter(c -> PlantQuery.binomial(c.scientificName()).equals(wanted))
                .findFirst();
    }

    /**
     * Is this a real species, and what does it look like? Checks the species
     * the AI suggests when identifying a plant from a description (the photo
     * path gets both answers from Pl@ntNet).
     *
     *   1. Wikipedia: nearly every species has a page with a photo, and it
     *      needs no key or quota.
     *   2. Perenual, when Wikipedia has no page about it. Its free plan covers
     *      only part of its catalogue, so it can't be the only check: a real
     *      species missing from it used to be dropped.
     *
     * Empty only when both answered and neither knows the species (the AI made
     * the name up). Answers are cached.
     *
     * @throws ExternalServiceException when the species couldn't be checked
     *         (a source failed and the other didn't find it)
     */
    public Optional<PlantCandidate> findSpecies(String scientificName) {
        String name = scientificName.trim();
        String key = PlantQuery.binomial(name);
        Optional<PlantCandidate> cached = speciesCache.get(key);
        if (cached != null) {
            return cached;
        }
        ExternalServiceException failure = null;
        try {
            Optional<WebResult> page = webSearch.page(name).filter(p -> describes(p, name));
            if (page.isPresent()) {
                return remember(key, Optional.of(new PlantCandidate(name, null, List.of(), genus(name), null, 0,
                        page.get().imageUrl())));
            }
        } catch (ExternalServiceException e) {
            failure = e;
        }
        try {
            Optional<PlantCandidate> inPerenual = verifySpecies(name);
            if (inPerenual.isPresent() || failure == null) {
                return remember(key, inPerenual);
            }
        } catch (ExternalServiceException e) {
            if (failure == null) {
                failure = e;
            }
        }
        throw failure;
    }

    /**
     * The page is about this species: it names it, or Wikipedia redirected the
     * name to the species' newer name, which keeps the species part in another
     * genus (Hylocereus undatus is now Selenicereus undatus). A redirect to the
     * genus' page (as for some rare species) is not.
     */
    static boolean describes(WebResult page, String scientificName) {
        if (PlantQuery.isAbout(page.title(), page.extract(), scientificName)) {
            return true;
        }
        String[] asked = PlantQuery.binomial(scientificName).split(" ");
        String[] found = PlantQuery.binomial(page.title()).split(" ");
        return asked.length == 2 && found.length == 2 && asked[1].equals(found[1]);
    }

    private Optional<PlantCandidate> remember(String key, Optional<PlantCandidate> answer) {
        speciesCache.put(key, answer);
        return answer;
    }

    private static String genus(String scientificName) {
        return scientificName.split("\\s+")[0];
    }

    private Optional<PlantCareProfile> tryPrimary(PlantQuery query, List<String> notes) {
        PlantQuery attempt = query;
        for (int i = 1; i <= MAX_PRIMARY_ATTEMPTS; i++) {
            try {
                Optional<PlantCareProfile> found = primary.getCareProfile(attempt);
                if (found.isPresent()) {
                    if (i > 1) {
                        notes.add(primary.name() + " found the plant on the retry (searched \""
                                + attempt.searchTerm() + "\").");
                    }
                    return found;
                }
                notes.add(primary.name() + " has no entry for \"" + attempt.searchTerm() + "\".");
                // Not found: retrying the identical search is pointless, so broaden it, or stop when
                // there is no other name to search for (each search uses Perenual's small daily quota).
                if (query.commonName() == null || query.commonName().equalsIgnoreCase(attempt.searchTerm())) {
                    break;
                }
                attempt = query.withSearchTerm(query.commonName());
            } catch (ExternalServiceException e) {
                log.info("Care lookup attempt {} via {} failed: {}", i, primary.name(), e.getMessage());
                notes.add(e.getMessage());
                if (!e.isRetryable()) {
                    break;
                }
            }
        }
        return Optional.empty();
    }

    private Optional<PlantCareProfile> tryFallback(PlantQuery query, List<String> notes) {
        notes.add("Asked " + fallback.name() + " to fill in the care template instead.");
        Optional<PlantCareProfile> profile = fallback.getCareProfile(query);
        if (profile.isEmpty()) {
            throw new ExternalServiceException(fallback.name(),
                    "could not provide care information for " + query.scientificName() + ".", true);
        }
        return profile;
    }
}
