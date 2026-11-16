package com.plantorganizer.common;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Pulls the JSON object out of an LLM's free-text answer. Used where the
 * model cannot be forced into JSON mode (image requests), so the reply may be
 * wrapped in ```json fences or have a sentence before it.
 */
public final class JsonExtractor {

    private JsonExtractor() {
    }

    public static JsonNode firstObject(JsonMapper mapper, String text, String service) {
        if (text != null) {
            int start = text.indexOf('{');
            int end = text.lastIndexOf('}');
            if (start >= 0 && end > start) {
                try {
                    return mapper.readTree(text.substring(start, end + 1));
                } catch (JacksonException ignored) {
                    // fall through to the error below
                }
            }
        }
        throw new ExternalServiceException(service, "did not return the expected JSON answer.", true);
    }
}
