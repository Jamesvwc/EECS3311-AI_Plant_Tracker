package com.plantorganizer.websearch;

import java.util.List;
import java.util.Optional;

import com.plantorganizer.common.ExternalServiceException;

/**
 * Target interface (Adapter pattern) for web search. The agent uses it to
 * retrieve real reference text before asking the LLM (retrieval-augmented
 * generation), so AI answers are grounded in a source the user can open.
 */
public interface WebSearch {

    /**
     * @return up to {@code maxResults} results, best first; empty when nothing matched
     * @throws ExternalServiceException when the search service could not be reached
     */
    List<WebResult> search(String query, int maxResults);

    /**
     * The page with exactly this title (following redirects, so a species'
     * older name finds its page), with its main photo.
     *
     * @return empty when there is no such page
     * @throws ExternalServiceException when the service could not be reached
     */
    Optional<WebResult> page(String title);
}
