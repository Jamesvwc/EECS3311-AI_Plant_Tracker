package com.plantorganizer.recommendation;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.plantorganizer.common.ExternalServiceException;
import com.plantorganizer.common.JsonValues;
import com.plantorganizer.llm.LLMClient;
import com.plantorganizer.plantinfo.PlantInfoService;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Shared steps of both recommendation strategies: ask the LLM for candidate
 * species as JSON, then check each against the Perenual database (Stage 1:
 * "each is validated against Perenual"). A candidate Perenual cannot confirm
 * is kept but marked unverified, so a database outage does not empty the list.
 */
@Component
class CandidateGenerator {

    private static final Logger log = LoggerFactory.getLogger(CandidateGenerator.class);

    /**
     * The JSON template the LLM fills. Cohere requires every object in a schema, nested ones
     * included, to list at least one "required" field, or it rejects the request with HTTP 400.
     * parsedConstraints itself stays optional (only UserConstraintStrategy asks for it), but when
     * the model includes it, all four fields must be present.
     */
    static final String SCHEMA = """
            {
              "type": "object",
              "properties": {
                "parsedConstraints": {"type": "object", "properties": {
                  "maxCareLevel": {"type": "string"},
                  "petSafeOnly": {"type": "boolean"},
                  "wateringPreference": {"type": "string"},
                  "light": {"type": "string"}
                }, "required": ["maxCareLevel", "petSafeOnly", "wateringPreference", "light"]},
                "recommendations": {"type": "array", "items": {"type": "object", "properties": {
                  "scientificName": {"type": "string"},
                  "commonName": {"type": "string"},
                  "reason": {"type": "string"},
                  "careLevel": {"type": "string"},
                  "recommendedSpace": {"type": "string"},
                  "sunlight": {"type": "string"},
                  "watering": {"type": "string"},
                  "petSafe": {"type": "boolean"}
                }, "required": ["scientificName", "commonName", "reason", "careLevel", "sunlight", "watering"]}}
              },
              "required": ["recommendations"]
            }
            """;

    private final LLMClient llm;
    private final PlantInfoService plantInfoService;
    private final JsonNode schema;

    CandidateGenerator(LLMClient llm, PlantInfoService plantInfoService, JsonMapper jsonMapper) {
        this.llm = llm;
        this.plantInfoService = plantInfoService;
        this.schema = jsonMapper.readTree(SCHEMA);
    }

    JsonNode ask(String instructions, String prompt) {
        if (!llm.isConfigured()) {
            throw new ExternalServiceException("Cohere",
                    "API key is not configured, so the agent cannot make recommendations. Add COHERE_API_KEY to .env.", false);
        }
        return llm.generateStructured(instructions, prompt, schema);
    }

    /** Reads the "recommendations" array, skipping excluded names and duplicates. */
    List<RecommendedPlant> read(long userId, JsonNode json, RecommendationContext ctx) {
        List<RecommendedPlant> plants = new ArrayList<>();
        List<String> seen = new ArrayList<>();
        for (JsonNode r : json.path("recommendations")) {
            String scientific = JsonValues.text(r.path("scientificName"));
            if (scientific == null || isExcluded(scientific, ctx) || seen.contains(scientific.toLowerCase(Locale.ROOT))
                    || isExcluded(JsonValues.text(r.path("commonName")), ctx)) {
                continue;
            }
            seen.add(scientific.toLowerCase(Locale.ROOT));
            plants.add(new RecommendedPlant(userId, scientific, JsonValues.text(r.path("commonName")),
                    JsonValues.text(r.path("reason")), JsonValues.text(r.path("careLevel")),
                    JsonValues.text(r.path("recommendedSpace")), JsonValues.text(r.path("sunlight")),
                    JsonValues.text(r.path("watering")), JsonValues.bool(r.path("petSafe"))));
        }
        return plants;
    }

    /** Confirms each species exists in Perenual and borrows its photo. */
    void verify(List<RecommendedPlant> plants) {
        for (RecommendedPlant plant : plants) {
            try {
                plantInfoService.verifySpecies(plant.getScientificName())
                        .ifPresent(match -> plant.markVerified(match.referenceImageUrl()));
            } catch (ExternalServiceException e) {
                log.info("Could not verify {}: {}", plant.getScientificName(), e.getMessage());
            }
        }
    }

    static String describe(RecommendationContext ctx) {
        StringBuilder sb = new StringBuilder();
        sb.append("Plants the user owns: ").append(listOrNone(ctx.ownedPlants())).append('\n');
        sb.append("User's spaces: ").append(listOrNone(ctx.spaces())).append('\n');
        sb.append("Watering habits: ").append(ctx.wateringHabit()).append('\n');
        sb.append("Plants/traits the user liked: ").append(listOrNone(ctx.preferences().likes())).append('\n');
        sb.append("Plants the user rejected (never suggest these or close relatives): ")
          .append(listOrNone(ctx.preferences().dislikes())).append('\n');
        sb.append("Things the user told the agent: ").append(listOrNone(ctx.preferences().notes())).append('\n');
        sb.append("Do not suggest any of these (already owned or listed): ").append(listOrNone(ctx.exclude())).append('\n');
        return sb.toString();
    }

    private static boolean isExcluded(String name, RecommendationContext ctx) {
        if (name == null) {
            return false;
        }
        String n = name.toLowerCase(Locale.ROOT);
        return ctx.exclude().stream().anyMatch(e -> e.equalsIgnoreCase(name)) || ctx.preferences().dislikes(n);
    }

    private static String listOrNone(List<String> items) {
        return items.isEmpty() ? "none" : String.join("; ", items);
    }
}
