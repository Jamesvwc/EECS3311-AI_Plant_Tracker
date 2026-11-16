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

/**
 * Finds care information for a confirmed species (Stage 1 UC13 "Look Up Care
 * Info", steps 5-6a of the identification workflow).
 *
 * Decision logic:
 *   1. Ask the primary source (Perenual) with the scientific name.
 *   2. If that fails or finds nothing, retry once: with the common name when
 *      the species was not found, or with the same query after a temporary
 *      error. A failure that cannot change on retry (bad key, plan limit)
 *      skips the retry.
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
    private final Map<String, CareLookup> cache = new ConcurrentHashMap<>();
    private final Map<String, List<PlantCandidate>> searchCache = new ConcurrentHashMap<>();

    public PlantInfoService(@Qualifier("perenualAdapter") PlantInfoProvider primary,
                            @Qualifier("llmCareInfoAdapter") PlantInfoProvider fallback) {
        this.primary = primary;
        this.fallback = fallback;
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
                // Not found: retrying the identical search is pointless, so broaden it.
                attempt = query.commonName() != null ? query.withSearchTerm(query.commonName()) : query;
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
