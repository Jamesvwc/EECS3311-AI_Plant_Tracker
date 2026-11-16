package com.plantorganizer.websearch;

import java.util.List;

/**
 * The answer from WebSearchTool: text the agent wrote from the retrieved
 * pages, plus those pages so the user can check them.
 *
 * @param wateringFrequency set only when the research was about watering (F02 "search for longer")
 */
public record ResearchResult(String answer, List<WebResult> sources, boolean aiWritten, String watering,
                             String wateringFrequency) {

    public ResearchResult {
        sources = sources == null ? List.of() : List.copyOf(sources);
    }
}
