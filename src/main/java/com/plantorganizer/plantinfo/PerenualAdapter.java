package com.plantorganizer.plantinfo;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import com.plantorganizer.common.ApiKeys;
import com.plantorganizer.common.ExternalServiceException;
import com.plantorganizer.common.JsonValues;
import com.plantorganizer.identification.PlantCandidate;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Adapter (Adapter pattern) from the Perenual plant database API to
 * {@link PlantInfoProvider}.
 *
 * Perenual needs two calls:
 *   1. GET /api/v2/species-list?key=..&q=TERM  returns matching species with ids
 *   2. GET /api/v2/species/details/{id}?key=.. returns watering, sunlight, care level...
 * The free plan only includes full details for part of the catalogue. For
 * other species the fields contain "Upgrade Plans To Premium..." text instead
 * of data, which this adapter reports as a non-retryable failure so the
 * system falls back to the AI source instead of showing that text.
 */
@Component
public class PerenualAdapter implements PlantInfoProvider {

    private static final Logger log = LoggerFactory.getLogger(PerenualAdapter.class);
    static final String SERVICE = "Perenual";
    private static final String UPGRADE_MARKER = "upgrade plan";

    private final RestClient restClient;
    private final JsonMapper jsonMapper;
    private final String apiKey;

    public PerenualAdapter(ClientHttpRequestFactory requestFactory,
                           JsonMapper jsonMapper,
                           @Value("${plantorganizer.perenual.base-url:https://perenual.com}") String baseUrl,
                           @Value("${plantorganizer.perenual.api-key}") String apiKey) {
        this.restClient = RestClient.builder()
                .requestFactory(requestFactory)
                .baseUrl(baseUrl)
                .build();
        this.jsonMapper = jsonMapper;
        this.apiKey = apiKey;
    }

    @Override
    public String name() {
        return SERVICE;
    }

    @Override
    public Optional<PlantCareProfile> getCareProfile(PlantQuery query) {
        if (!ApiKeys.isConfigured(apiKey)) {
            throw new ExternalServiceException(SERVICE,
                    "API key is not configured. Add PERENUAL_API_KEY to your .env file.", false);
        }
        String searchBody = get("/api/v2/species-list", query.searchTerm(), null);
        Optional<Long> speciesId = findMatchingSpeciesId(searchBody, query.scientificName());
        if (speciesId.isEmpty()) {
            return Optional.empty();
        }
        String detailsBody = get("/api/v2/species/details/{id}", null, speciesId.get());
        return Optional.of(parseDetails(detailsBody));
    }

    /** Species search (Stage 1 searchByName): one call to species-list. */
    @Override
    public List<PlantCandidate> searchByName(String name) {
        if (!ApiKeys.isConfigured(apiKey)) {
            throw new ExternalServiceException(SERVICE,
                    "API key is not configured. Add PERENUAL_API_KEY to your .env file.", false);
        }
        return parseSearch(get("/api/v2/species-list", name, null));
    }

    /** Package-private for unit tests. */
    List<PlantCandidate> parseSearch(String searchBody) {
        List<PlantCandidate> results = new ArrayList<>();
        for (JsonNode plant : jsonMapper.readTree(searchBody).path("data")) {
            String scientific = firstOf(JsonValues.textList(plant.path("scientific_name")));
            if (scientific == null) {
                continue;
            }
            String common = JsonValues.text(plant.path("common_name"));
            results.add(new PlantCandidate(scientific, null, common == null ? List.of() : List.of(common),
                    JsonValues.text(plant.path("genus")), JsonValues.text(plant.path("family")), 0,
                    image(plant.path("default_image"))));
        }
        return results;
    }

    /**
     * Picks the first search hit whose scientific name has the same genus and
     * species as the confirmed plant. Package-private for unit tests.
     */
    Optional<Long> findMatchingSpeciesId(String searchBody, String scientificName) {
        String wanted = PlantQuery.binomial(scientificName);
        for (JsonNode plant : jsonMapper.readTree(searchBody).path("data")) {
            for (String name : JsonValues.textList(plant.path("scientific_name"))) {
                if (PlantQuery.binomial(name).equals(wanted) && plant.path("id").isNumber()) {
                    return Optional.of(plant.path("id").longValue());
                }
            }
        }
        return Optional.empty();
    }

    /** Maps Perenual's details JSON to a PlantCareProfile. Package-private for unit tests. */
    PlantCareProfile parseDetails(String detailsBody) {
        if (detailsBody.toLowerCase().contains(UPGRADE_MARKER)) {
            throw new ExternalServiceException(SERVICE,
                    "the free plan does not include care details for this species.", false);
        }
        JsonNode d = jsonMapper.readTree(detailsBody);
        return new PlantCareProfile(
                firstOf(JsonValues.textList(d.path("scientific_name"))),
                JsonValues.text(d.path("common_name")),
                JsonValues.text(d.path("family")),
                JsonValues.text(d.path("description")),
                JsonValues.text(d.path("watering")),
                wateringFrequency(d.path("watering_general_benchmark")),
                JsonValues.textList(d.path("sunlight")),
                JsonValues.text(d.path("care_level")),
                JsonValues.text(d.path("growth_rate")),
                JsonValues.bool(d.path("indoor")),
                JsonValues.bool(d.path("poisonous_to_pets")),
                JsonValues.bool(d.path("poisonous_to_humans")),
                hardiness(d.path("hardiness")),
                image(d.path("default_image")),
                InfoSource.PERENUAL,
                null);
    }

    private String get(String path, String searchTerm, Long id) {
        try {
            return restClient.get()
                    .uri(uri -> {
                        uri.path(path).queryParam("key", apiKey);
                        if (searchTerm != null) {
                            uri.queryParam("q", searchTerm);
                        }
                        return id == null ? uri.build() : uri.build(id);
                    })
                    .retrieve()
                    .body(String.class);
        } catch (RestClientResponseException e) {
            int status = e.getStatusCode().value();
            String said = message(e.getResponseBodyAsString());
            log.warn("Perenual returned HTTP {} for {}: {}", status, path, said);
            throw new ExternalServiceException(SERVICE, reason(status, said), status >= 500, e);
        } catch (ResourceAccessException e) {
            throw new ExternalServiceException(SERVICE, "could not be reached (" + e.getMessage() + ")", true, e);
        }
    }

    /**
     * Why a request failed, in words. Perenual answers a missing, wrong or
     * inactive key with HTTP 404 "Missing/Issue with API Key", not 401.
     * Package-private for unit tests.
     */
    static String reason(int status, String said) {
        String quoted = said == null ? "" : " (Perenual said: \"" + said + "\")";
        if (status == 401 || status == 403 || (said != null && said.toLowerCase().contains("api key"))) {
            return "rejected the API key" + quoted + ". Check PERENUAL_API_KEY in .env, and that the key "
                    + "is active in your account on perenual.com.";
        }
        if (status == 429) {
            return "daily request limit reached" + quoted + ". It resets tomorrow.";
        }
        return "returned HTTP " + status + quoted + ".";
    }

    /** The "message" in Perenual's JSON error body, or null. */
    private String message(String body) {
        try {
            return JsonValues.text(jsonMapper.readTree(body).path("message"));
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** {"value": "7-10", "unit": "days"} -> "every 7-10 days". Perenual sometimes wraps the value in quotes. */
    private static String wateringFrequency(JsonNode benchmark) {
        String value = JsonValues.text(benchmark.path("value"));
        if (value == null) {
            return null;
        }
        value = value.replace("\"", "").trim();
        String unit = JsonValues.text(benchmark.path("unit"));
        return "every " + value + (unit == null ? "" : " " + unit);
    }

    private static String hardiness(JsonNode hardiness) {
        String min = JsonValues.text(hardiness.path("min"));
        String max = JsonValues.text(hardiness.path("max"));
        if (min == null && max == null) {
            return null;
        }
        if (min == null || max == null || min.equals(max)) {
            return "Zone " + (min != null ? min : max);
        }
        return "Zones " + min + "-" + max;
    }

    private static String image(JsonNode image) {
        for (String field : new String[] {"medium_url", "regular_url", "small_url", "original_url"}) {
            String url = JsonValues.text(image.path(field));
            if (url != null) {
                return url;
            }
        }
        return null;
    }

    private static String firstOf(java.util.List<String> values) {
        return values.isEmpty() ? null : values.get(0);
    }
}
