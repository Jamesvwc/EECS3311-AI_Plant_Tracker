package com.plantorganizer.identification;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import com.plantorganizer.common.ApiKeys;
import com.plantorganizer.common.ExternalServiceException;
import com.plantorganizer.common.JsonValues;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Adapter (Adapter pattern) from the Pl@ntNet identification API to the
 * {@link PlantIdentifier} interface.
 *
 * Pl@ntNet expects a multipart POST to /v2/identify/all with the key as the
 * {@code api-key} query parameter, one {@code images} part per photo and a
 * matching {@code organs} part ("auto" lets Pl@ntNet detect leaf/flower/etc.).
 * It answers with a JSON list of species and scores, or HTTP 404 when no
 * plant is recognised. This class hides all of that and returns plain
 * {@link PlantCandidate} objects.
 */
@Component
public class PlantNetAdapter implements PlantIdentifier {

    private static final Logger log = LoggerFactory.getLogger(PlantNetAdapter.class);
    static final String SERVICE = "Pl@ntNet";

    private final RestClient restClient;
    private final JsonMapper jsonMapper;
    private final String apiKey;

    public PlantNetAdapter(ClientHttpRequestFactory requestFactory,
                           JsonMapper jsonMapper,
                           @Value("${plantorganizer.plantnet.base-url:https://my-api.plantnet.org}") String baseUrl,
                           @Value("${plantorganizer.plantnet.api-key}") String apiKey) {
        this.restClient = RestClient.builder()
                .requestFactory(requestFactory)
                .baseUrl(baseUrl)
                .build();
        this.jsonMapper = jsonMapper;
        this.apiKey = apiKey;
    }

    @Override
    public IdentificationResult identify(List<PlantImage> images, int maxResults) {
        if (!ApiKeys.isConfigured(apiKey)) {
            throw new ExternalServiceException(SERVICE,
                    "API key is not configured. Add PLANTNET_API_KEY to your .env file.", false);
        }

        MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        for (PlantImage image : images) {
            form.add("images", asNamedResource(image));
            form.add("organs", "auto");
        }

        try {
            String body = restClient.post()
                    .uri(uri -> uri.path("/v2/identify/all")
                            .queryParam("api-key", apiKey)
                            .queryParam("nb-results", maxResults)
                            .queryParam("lang", "en")
                            .queryParam("include-related-images", true)
                            .build())
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(form)
                    .retrieve()
                    .body(String.class);
            return parse(body, images.size(), maxResults);

        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == HttpStatus.NOT_FOUND.value()) {
                // Pl@ntNet's documented "Species not found": a valid answer, not a failure.
                return new IdentificationResult(List.of(), images.size(), null);
            }
            throw translate(e);
        } catch (ResourceAccessException e) {
            throw new ExternalServiceException(SERVICE, "could not be reached (" + e.getMessage() + ")", true, e);
        }
    }

    /** Converts Pl@ntNet's JSON into candidates. Package-private for unit tests. */
    IdentificationResult parse(String body, int photosUsed, int maxResults) {
        JsonNode root = jsonMapper.readTree(body);
        List<PlantCandidate> candidates = new ArrayList<>();
        for (JsonNode result : root.path("results")) {
            if (candidates.size() >= maxResults) {
                break;
            }
            JsonNode species = result.path("species");
            String scientificName = JsonValues.text(species.path("scientificNameWithoutAuthor"));
            if (scientificName == null) {
                continue;
            }
            candidates.add(new PlantCandidate(
                    scientificName,
                    JsonValues.text(species.path("scientificNameAuthorship")),
                    JsonValues.textList(species.path("commonNames")),
                    JsonValues.text(species.path("genus").path("scientificNameWithoutAuthor")),
                    JsonValues.text(species.path("family").path("scientificNameWithoutAuthor")),
                    result.path("score").asDouble(0.0),
                    firstReferenceImage(result.path("images"))));
        }
        JsonNode remaining = root.path("remainingIdentificationRequests");
        Integer remainingRequests = remaining.isNumber() ? remaining.intValue() : null;
        return new IdentificationResult(candidates, photosUsed, remainingRequests);
    }

    private static String firstReferenceImage(JsonNode images) {
        for (JsonNode image : images) {
            JsonNode url = image.path("url");
            for (String size : new String[] {"m", "s", "o"}) {
                String value = JsonValues.text(url.path(size));
                if (value != null) {
                    return value;
                }
            }
        }
        return null;
    }

    /** Multipart file parts need a file name, which a plain byte[] does not carry. */
    private static ByteArrayResource asNamedResource(PlantImage image) {
        return new ByteArrayResource(image.data()) {
            @Override
            public String getFilename() {
                return image.filename();
            }
        };
    }

    private ExternalServiceException translate(RestClientResponseException e) {
        int status = e.getStatusCode().value();
        log.warn("Pl@ntNet returned HTTP {}: {}", status, e.getResponseBodyAsString());
        return switch (status) {
            case 400 -> new ExternalServiceException(SERVICE,
                    "rejected the photos (HTTP 400). Try clearer JPEG/PNG photos of the plant.", false, e);
            case 401, 403 -> new ExternalServiceException(SERVICE,
                    "rejected the API key (HTTP " + status + "). Check PLANTNET_API_KEY in .env.", false, e);
            case 413 -> new ExternalServiceException(SERVICE, "photos are too large (HTTP 413).", false, e);
            case 429 -> new ExternalServiceException(SERVICE,
                    "daily identification quota is used up (HTTP 429). Try again tomorrow.", false, e);
            default -> new ExternalServiceException(SERVICE, "returned HTTP " + status + ".", status >= 500, e);
        };
    }
}
