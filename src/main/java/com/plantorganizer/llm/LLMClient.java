package com.plantorganizer.llm;

import java.util.List;

import com.plantorganizer.common.ExternalServiceException;
import com.plantorganizer.identification.PlantImage;

import tools.jackson.databind.JsonNode;

/**
 * Target interface of the Adapter pattern for large language models.
 *
 * Stage 1 named the implementation ClaudeAdapter; Stage 2 uses Cohere
 * (CohereAdapter) because it has a free trial tier. Because the agent only
 * depends on this interface, that swap did not touch any other class.
 */
public interface LLMClient {

    /** True when the model can be called (an API key is configured). */
    boolean isConfigured();

    /**
     * Free-text generation.
     *
     * @param instructions system prompt: the role and rules the model must follow
     * @param prompt       the user-facing request, including any context data
     */
    String generate(String instructions, String prompt);

    /**
     * Structured generation: the model must fill in a JSON template.
     *
     * @param instructions system prompt
     * @param prompt       the request; should say that JSON is expected
     * @param jsonSchema   JSON Schema the answer must follow
     * @return the parsed JSON object produced by the model
     */
    JsonNode generateStructured(String instructions, String prompt, JsonNode jsonSchema);

    /** True when the model can also look at photos (F03 timetable photos, F05 plant photos). */
    default boolean supportsVision() {
        return false;
    }

    /**
     * Structured generation from text plus photos (vision model).
     *
     * @param images photos to look at, in order (e.g. current plant photo, then an older one)
     */
    default JsonNode generateStructuredWithImages(String instructions, String prompt, List<PlantImage> images,
                                                  JsonNode jsonSchema) {
        throw new ExternalServiceException("LLM", "this model cannot read images.", false);
    }
}
