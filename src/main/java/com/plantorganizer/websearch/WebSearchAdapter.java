package com.plantorganizer.websearch;

import java.util.ArrayList;
import java.util.List;

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
 * GET /api/rest_v1/page/summary/{title} returns each page's plain-text extract.
 * Wikipedia asks API clients to send a descriptive User-Agent.
 */
@Component
public class WebSearchAdapter implements WebSearch {

    private static final Logger log = LoggerFactory.getLogger(WebSearchAdapter.class);
    static final String SERVICE = "Wikipedia";

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
                String extract = summary(key);
                if (extract == null) {
                    extract = stripTags(JsonValues.text(page.path("excerpt")));
                }
                results.add(new WebResult(title, extract, baseUrl + "/wiki/" + key));
            }
        } catch (RestClientException e) {
            throw new ExternalServiceException(SERVICE, "search failed (" + e.getMessage() + ")", true, e);
        }
        return results;
    }

    private String summary(String key) {
        try {
            String body = restClient.get()
                    .uri("/api/rest_v1/page/summary/{title}", key)
                    .retrieve()
                    .body(String.class);
            return JsonValues.text(jsonMapper.readTree(body).path("extract"));
        } catch (RestClientException e) {
            log.debug("No Wikipedia summary for {}: {}", key, e.getMessage());
            return null;
        }
    }

    static String stripTags(String html) {
        return html == null ? null : html.replaceAll("<[^>]+>", "").replace("&quot;", "\"").replace("&amp;", "&");
    }
}
