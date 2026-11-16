package com.plantorganizer.agent.tools;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.plantorganizer.common.ExternalServiceException;
import com.plantorganizer.common.JsonValues;
import com.plantorganizer.llm.LLMClient;
import com.plantorganizer.websearch.ResearchResult;
import com.plantorganizer.websearch.WebResult;
import com.plantorganizer.websearch.WebSearch;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Concrete tool (Stage 1 WebSearchTool): "search for longer". Retrieves pages
 * with {@link WebSearch} and has the LLM answer the question from them,
 * citing the pages (retrieval-augmented generation).
 *
 * Used when the user is not satisfied with a health check (F05), when the
 * watering data was thin (F02 mode "watering" also extracts a frequency), and
 * when the wishlist fit check has no care data (F09).
 *
 * Arguments: query, question, context (optional), mode ("answer" or "watering").
 */
public class WebSearchTool implements Tool {

    private static final String INSTRUCTIONS = """
            You are a plant-care research assistant. Answer the question using the numbered web sources
            provided; cite them like [1]. If the sources do not cover something, say so and give general
            guidance clearly labelled as general. Be practical and concise (under 220 words), use "- "
            bullets for steps, and end with one sign the owner should watch for so they learn to judge it
            themselves.
            """;
    private static final String WATERING_SCHEMA = """
            {"type": "object", "properties": {
               "answer": {"type": "string"}, "found": {"type": "boolean"},
               "watering": {"type": "string"}, "wateringFrequency": {"type": "string"}},
             "required": ["answer", "found", "watering", "wateringFrequency"]}
            """;

    private final WebSearch webSearch;
    private final LLMClient llm;
    private final JsonNode wateringSchema;

    WebSearchTool(WebSearch webSearch, LLMClient llm, JsonMapper jsonMapper) {
        this.webSearch = webSearch;
        this.llm = llm;
        this.wateringSchema = jsonMapper.readTree(WATERING_SCHEMA);
    }

    @Override
    public ToolType type() {
        return ToolType.WEB_SEARCH;
    }

    @Override
    public ResearchResult execute(Map<String, Object> args) {
        String query = ToolArgs.required(args, "query", String.class);
        String question = ToolArgs.required(args, "question", String.class);
        String context = ToolArgs.optional(args, "context", String.class);
        boolean wateringMode = "watering".equals(ToolArgs.optional(args, "mode", String.class));

        List<WebResult> sources = new ArrayList<>();
        try {
            sources.addAll(webSearch.search(query, 3));
        } catch (ExternalServiceException e) {
            // keep going: the LLM can still give clearly-labelled general guidance
        }
        if (!llm.isConfigured()) {
            StringBuilder sb = new StringBuilder(sources.isEmpty() ? "I couldn't find anything on the web for that."
                    : "Here is what I found on the web:\n");
            sources.forEach(s -> sb.append("- **").append(s.title()).append(":** ").append(shorten(s.extract())).append('\n'));
            return new ResearchResult(sb.toString().trim(), sources, false, null, null);
        }
        String prompt = "Question: " + question + "\n" + (context != null ? "Context: " + context + "\n" : "")
                + "\nSources:\n" + numbered(sources);
        if (wateringMode) {
            JsonNode json = llm.generateStructured(INSTRUCTIONS
                    + "\nAlso fill watering (\"Frequent\", \"Average\" or \"Minimum\") and wateringFrequency "
                    + "(e.g. \"every 7-10 days\"); set found to false if the sources don't say.", prompt, wateringSchema);
            boolean found = Boolean.TRUE.equals(JsonValues.bool(json.path("found")));
            return new ResearchResult(JsonValues.text(json.path("answer")), sources, true,
                    found ? JsonValues.text(json.path("watering")) : null,
                    found ? JsonValues.text(json.path("wateringFrequency")) : null);
        }
        return new ResearchResult(llm.generate(INSTRUCTIONS, prompt), sources, true, null, null);
    }

    private static String numbered(List<WebResult> sources) {
        if (sources.isEmpty()) {
            return "(no sources found)";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < sources.size(); i++) {
            sb.append('[').append(i + 1).append("] ").append(sources.get(i).title()).append(": ")
              .append(sources.get(i).extract()).append('\n');
        }
        return sb.toString();
    }

    private static String shorten(String s) {
        return s == null ? "" : s.length() > 300 ? s.substring(0, 300) + "..." : s;
    }
}
