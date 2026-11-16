package com.plantorganizer.plantinfo;

import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Component;

import com.plantorganizer.common.ExternalServiceException;
import com.plantorganizer.common.JsonValues;
import com.plantorganizer.llm.LLMClient;
import com.plantorganizer.websearch.WebResult;
import com.plantorganizer.websearch.WebSearch;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Fallback care-information source: adapts an {@link LLMClient} to the
 * {@link PlantInfoProvider} interface (Adapter pattern).
 *
 * Stage 1 planned the agent to "search the web and fill a care template".
 * Cohere's Chat API has no built-in web search, so this adapter first
 * retrieves reference text with {@link WebSearch} (Wikipedia, see
 * WebSearchAdapter) and gives it to the model together with the template
 * (retrieval-augmented generation). If the search fails the model answers from
 * its own knowledge. Every profile it produces is labelled AI_GENERATED
 * ("lower reliability") in the GUI.
 *
 * Stage 1 step 6b: "the AI agent will fill out a template of information that
 * the system will be able to parse". The template is the JSON Schema below;
 * Cohere's structured-output mode guarantees the reply matches it.
 */
@Component
public class LlmCareInfoAdapter implements PlantInfoProvider {

    private static final String INSTRUCTIONS = """
            You are a careful horticulture reference. You fill in plant-care templates as JSON.
            Only state facts you are confident about; leave out any optional field you do not know.
            If you do not have reliable knowledge of the exact species requested, set
            knownSpecies to false, put the name of the most similar plant you DO know in
            similarPlantName, and describe that similar plant instead.
            Keep "description" to 2-3 sentences. Use plain words like "Average", "Frequent"
            or "Minimum" for watering, and values like "full sun", "part shade" for sunlight.
            """;

    private final LLMClient llm;
    private final WebSearch webSearch;
    private final JsonNode careTemplate;

    public LlmCareInfoAdapter(LLMClient llm, WebSearch webSearch, JsonMapper jsonMapper) {
        this.llm = llm;
        this.webSearch = webSearch;
        this.careTemplate = jsonMapper.readTree(CARE_TEMPLATE_SCHEMA);
    }

    @Override
    public String name() {
        return "Cohere AI";
    }

    @Override
    public Optional<PlantCareProfile> getCareProfile(PlantQuery query) {
        if (!llm.isConfigured()) {
            throw new ExternalServiceException("Cohere",
                    "API key is not configured. Add COHERE_API_KEY to your .env file.", false);
        }
        String prompt = "Generate a JSON plant-care profile for the species \"" + query.scientificName() + "\""
                + (query.commonName() != null ? " (common name: \"" + query.commonName() + "\")" : "")
                + ". The user grows it as a house or garden plant." + webContext(query);
        JsonNode json = llm.generateStructured(INSTRUCTIONS, prompt, careTemplate);
        return Optional.of(toProfile(json, query));
    }

    /** Reference text from the web for the prompt, or "" when the search finds nothing or fails. */
    private String webContext(PlantQuery query) {
        List<WebResult> results;
        try {
            results = webSearch.search(query.scientificName(), 2);
        } catch (ExternalServiceException e) {
            return "";
        }
        if (results.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("\n\nReference text found on the web (use it where relevant):\n");
        for (WebResult r : results) {
            sb.append("- ").append(r.title()).append(": ").append(r.extract()).append('\n');
        }
        return sb.toString();
    }

    /** Package-private for unit tests. */
    static PlantCareProfile toProfile(JsonNode json, PlantQuery query) {
        boolean known = !Boolean.FALSE.equals(JsonValues.bool(json.path("knownSpecies")));
        String similar = known ? null : JsonValues.text(json.path("similarPlantName"));
        if (!known && similar == null) {
            similar = "an unnamed similar plant";
        }
        String scientificName = JsonValues.text(json.path("scientificName"));
        return new PlantCareProfile(
                scientificName != null ? scientificName : query.scientificName(),
                firstNonNull(JsonValues.text(json.path("commonName")), query.commonName()),
                JsonValues.text(json.path("family")),
                JsonValues.text(json.path("description")),
                JsonValues.text(json.path("watering")),
                JsonValues.text(json.path("wateringFrequency")),
                JsonValues.textList(json.path("sunlight")),
                JsonValues.text(json.path("careLevel")),
                JsonValues.text(json.path("growthRate")),
                JsonValues.bool(json.path("indoor")),
                JsonValues.bool(json.path("poisonousToPets")),
                JsonValues.bool(json.path("poisonousToHumans")),
                JsonValues.text(json.path("hardinessZones")),
                null,
                InfoSource.AI_GENERATED,
                similar);
    }

    private static String firstNonNull(String a, String b) {
        return a != null ? a : b;
    }

    /** The "template" from Stage 1, as a JSON Schema Cohere must follow. */
    private static final String CARE_TEMPLATE_SCHEMA = """
            {
              "type": "object",
              "properties": {
                "knownSpecies":      {"type": "boolean"},
                "similarPlantName":  {"type": "string"},
                "scientificName":    {"type": "string"},
                "commonName":        {"type": "string"},
                "family":            {"type": "string"},
                "description":       {"type": "string"},
                "watering":          {"type": "string"},
                "wateringFrequency": {"type": "string"},
                "sunlight":          {"type": "array", "items": {"type": "string"}},
                "careLevel":         {"type": "string"},
                "growthRate":        {"type": "string"},
                "indoor":            {"type": "boolean"},
                "poisonousToPets":   {"type": "boolean"},
                "poisonousToHumans": {"type": "boolean"},
                "hardinessZones":    {"type": "string"}
              },
              "required": ["knownSpecies", "scientificName", "watering", "wateringFrequency",
                           "sunlight", "careLevel", "description"]
            }
            """;
}
