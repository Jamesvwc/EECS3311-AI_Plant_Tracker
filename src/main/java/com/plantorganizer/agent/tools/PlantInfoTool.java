package com.plantorganizer.agent.tools;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.plantorganizer.common.ExternalServiceException;
import com.plantorganizer.common.JsonValues;
import com.plantorganizer.identification.IdentificationResult;
import com.plantorganizer.identification.PlantCandidate;
import com.plantorganizer.llm.LLMClient;
import com.plantorganizer.plantinfo.CareLookup;
import com.plantorganizer.plantinfo.PlantInfoService;
import com.plantorganizer.plantinfo.PlantQuery;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Concrete tool (Stage 1: "runs the care-info lookup and species search").
 *
 * With {@code "query"} (a PlantQuery): looks up care information through
 * PlantInfoService (Perenual, one retry, AI fallback) and returns a CareLookup.
 *
 * With {@code "description"} (text): the F01 text path. The LLM proposes
 * likely species, then PlantInfoService.findSpecies() keeps the real ones
 * (Wikipedia, then Perenual) and finds a reference photo of each, so the
 * matches look like the photo path's. Returns an IdentificationResult.
 */
public class PlantInfoTool implements Tool {

    public static final String ARG_QUERY = "query";
    public static final String ARG_DESCRIPTION = "description";

    private static final String INSTRUCTIONS = """
            You identify plants from a person's description (leaf shape, colour, size, flowers, where it
            grows). Propose up to 5 likely species, most likely first, using accepted scientific names
            (genus + species), with the everyday commonName of that species and its botanical family.
            confidence is 0-1. reason is one short sentence linking the description to the species.
            If the text is not about a plant, return an empty candidates array.
            """;
    private static final String SCHEMA = """
            {"type": "object", "properties": {"candidates": {"type": "array", "items": {"type": "object",
              "properties": {"scientificName": {"type": "string"}, "commonName": {"type": "string"},
                             "family": {"type": "string"}, "confidence": {"type": "number"},
                             "reason": {"type": "string"}},
              "required": ["scientificName", "confidence"]}}},
             "required": ["candidates"]}
            """;

    private final PlantInfoService plantInfoService;
    private final LLMClient llm;
    private final JsonNode schema;

    PlantInfoTool(PlantInfoService plantInfoService, LLMClient llm, JsonMapper jsonMapper) {
        this.plantInfoService = plantInfoService;
        this.llm = llm;
        this.schema = jsonMapper.readTree(SCHEMA);
    }

    @Override
    public ToolType type() {
        return ToolType.LOOKUP_PLANT_INFO;
    }

    @Override
    public Object execute(Map<String, Object> args) {
        PlantQuery query = ToolArgs.optional(args, ARG_QUERY, PlantQuery.class);
        if (query != null) {
            CareLookup lookup = plantInfoService.lookup(query);
            return lookup;
        }
        return identifyFromDescription(ToolArgs.required(args, ARG_DESCRIPTION, String.class));
    }

    private IdentificationResult identifyFromDescription(String description) {
        if (!llm.isConfigured()) {
            throw new ExternalServiceException("Cohere",
                    "API key is not configured, so text descriptions can't be identified. Upload a photo instead.", false);
        }
        JsonNode json = llm.generateStructured(INSTRUCTIONS, "Description: \"" + description.trim() + "\"", schema);
        List<PlantCandidate> verified = new ArrayList<>();
        List<PlantCandidate> unverified = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        for (JsonNode c : json.path("candidates")) {
            String scientific = JsonValues.text(c.path("scientificName"));
            if (scientific == null) {
                continue;
            }
            String common = JsonValues.text(c.path("commonName"));
            String family = JsonValues.text(c.path("family"));
            double confidence = Math.max(0, Math.min(1,
                    c.path("confidence").isNumber() ? c.path("confidence").doubleValue() : 0.3));
            try {
                plantInfoService.findSpecies(scientific).ifPresentOrElse(
                        match -> verified.add(new PlantCandidate(match.scientificName(), null,
                                names(common, match.primaryCommonName()), match.genus(),
                                match.family() != null ? match.family() : family, confidence,
                                match.referenceImageUrl())),
                        () -> notes.add("The AI suggested " + scientific + ", but neither Wikipedia nor "
                                + "Perenual knows a species by that name, so it was left out."));
            } catch (ExternalServiceException e) {
                unverified.add(new PlantCandidate(scientific, null, names(common, null), genus(scientific), family,
                        confidence, null));
                if (notes.stream().noneMatch(n -> n.startsWith("Could not check"))) {
                    notes.add("Could not check the AI's guesses (" + e.getMessage()
                            + "), so they are shown unverified and without photos.");
                }
            }
        }
        List<PlantCandidate> result = verified.isEmpty() ? unverified : verified;
        return new IdentificationResult(result.stream().limit(3).toList(), 0, null, notes);
    }

    private static String genus(String scientificName) {
        return scientificName.trim().split("\\s+")[0];
    }

    private static List<String> names(String a, String b) {
        List<String> names = new ArrayList<>();
        if (a != null) {
            names.add(a);
        }
        if (b != null && !b.equalsIgnoreCase(a)) {
            names.add(b);
        }
        return names;
    }
}
