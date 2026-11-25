package com.plantorganizer.websearch;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import com.plantorganizer.common.ExternalServiceException;
import com.plantorganizer.common.JsonValues;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Stage 1 WebSearchAdapter, implemented with Wikipedia's free public API (no
 * key needed): GET /w/rest.php/v1/search/page finds pages, then
 * GET /w/api.php (prop=extracts|pageimages) returns each page's introduction
 * as plain text and its main photo (from Wikimedia Commons). Wikipedia asks
 * API clients to send a descriptive User-Agent.
 */
@Component
public class WebSearchAdapter implements WebSearch {

    private static final Logger log = LoggerFactory.getLogger(WebSearchAdapter.class);
    static final String SERVICE = "Wikipedia";
    static final int MAX_EXTRACT = 1500;
    /** Width of the page photos asked for, in pixels (big enough for the GUI's match cards). */
    static final int PHOTO_WIDTH = 640;

    private final RestClient restClient;
    private final JsonMapper jsonMapper;
    private final String baseUrl;
    private final boolean enabled;

    public WebSearchAdapter(ClientHttpRequestFactory requestFactory,
                            JsonMapper jsonMapper,
                            @Value("${plantorganizer.websearch.base-url:https://en.wikipedia.org}") String baseUrl,
                            @Value("${plantorganizer.websearch.enabled:true}") boolean enabled) {
        this.restClient = RestClient.builder()
                .requestFactory(requestFactory)
                .baseUrl(baseUrl)
                .defaultHeader("User-Agent", "PlantOrganizer/0.2 (EECS3311 student project)")
                .build();
        this.jsonMapper = jsonMapper;
        this.baseUrl = baseUrl;
        this.enabled = enabled;
    }

    @Override
    public List<WebResult> search(String query, int maxResults) {
        if (!enabled || query == null || query.isBlank()) {
            return List.of();
        }
        List<WebResult> results = new ArrayList<>();
        try {
            String body = restClient.get()
                    .uri(uri -> uri.path("/w/rest.php/v1/search/page")
                            .queryParam("q", query)
                            .queryParam("limit", maxResults)
                            .build())
                    .retrieve()
                    .body(String.class);
            for (JsonNode page : jsonMapper.readTree(body).path("pages")) {
                String key = JsonValues.text(page.path("key"));
                String title = JsonValues.text(page.path("title"));
                if (key == null || title == null) {
                    continue;
                }
                Optional<WebResult> found = fetch(key);
                String extract = found.map(WebResult::extract).orElse(null);
                if (extract == null) {
                    extract = stripTags(JsonValues.text(page.path("excerpt")));
                }
                results.add(new WebResult(title, extract, baseUrl + "/wiki/" + key,
                        found.map(WebResult::imageUrl).orElse(null)));
            }
        } catch (RestClientException e) {
            throw new ExternalServiceException(SERVICE, "search failed (" + e.getMessage() + ")", true, e);
        }
        return results;
    }

    @Override
    public Optional<WebResult> page(String title) {
        if (!enabled || title == null || title.isBlank()) {
            return Optional.empty();
        }
        try {
            return read(title.strip());
        } catch (RestClientException e) {
            throw new ExternalServiceException(SERVICE, "page lookup failed (" + e.getMessage() + ")", true, e);
        }
    }

    /** A search hit's introduction and photo; empty when they can't be fetched (the search excerpt is used instead). */
    private Optional<WebResult> fetch(String key) {
        try {
            return read(key);
        } catch (RestClientException e) {
            log.debug("No Wikipedia introduction for {}: {}", key, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * One page: its introduction as plain text (at most {@link #MAX_EXTRACT}
     * characters) and its main photo. The short page summary is often a single
     * sentence; the introduction also has what the plant is called and how it
     * is grown (for Gymnocalycium mihanovichii, that its grafted form is the
     * "moon cactus").
     */
    private Optional<WebResult> read(String title) {
        String body = restClient.get()
                .uri(uri -> uri.path("/w/api.php")
                        .queryParam("action", "query")
                        .queryParam("prop", "extracts|pageimages")
                        .queryParam("exintro", 1)
                        .queryParam("explaintext", 1)
                        .queryParam("piprop", "thumbnail")
                        .queryParam("pithumbsize", PHOTO_WIDTH)
                        .queryParam("redirects", 1)
                        .queryParam("format", "json")
                        .queryParam("titles", title)
                        .build())
                .retrieve()
                .body(String.class);
        for (JsonNode page : jsonMapper.readTree(body).path("query").path("pages")) {
            String pageTitle = JsonValues.text(page.path("title"));
            if (page.has("missing") || page.has("invalid") || pageTitle == null) {
                continue;
            }
            String extract = JsonValues.text(page.path("extract"));
            return Optional.of(new WebResult(pageTitle,
                    extract == null ? null : shorten(extract.strip().replaceAll("\\n+", " ")),
                    baseUrl + "/wiki/" + pageTitle.replace(' ', '_'),
                    JsonValues.text(page.path("thumbnail").path("source"))));
        }
        return Optional.empty();
    }

    /** Cuts long text at the last full sentence within {@link #MAX_EXTRACT} characters. */
    static String shorten(String text) {
        if (text.length() <= MAX_EXTRACT) {
            return text;
        }
        String cut = text.substring(0, MAX_EXTRACT);
        int end = cut.lastIndexOf(". ");
        return end > 0 ? cut.substring(0, end + 1) : cut;
    }

    static String stripTags(String html) {
        return html == null ? null : html.replaceAll("<[^>]+>", "").replace("&quot;", "\"").replace("&amp;", "&");
    }
}
