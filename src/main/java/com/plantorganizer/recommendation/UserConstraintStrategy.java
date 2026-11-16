package com.plantorganizer.recommendation;

import java.util.List;
import java.util.Locale;

import org.springframework.stereotype.Component;

import com.plantorganizer.common.JsonValues;

import tools.jackson.databind.JsonNode;

/**
 * Concrete strategy (F07): recommends plants for constraints the user states
 * ("low maintenance, safe for cats, for my dark bedroom"). The LLM interprets
 * the constraints and proposes candidates; this class then filters the
 * candidates deterministically against the interpreted constraints, so a
 * suggestion that breaks a hard rule (e.g. not pet safe) is dropped.
 */
@Component
public class UserConstraintStrategy implements RecommendationStrategy {

    private static final String INSTRUCTIONS = """
            You are PlantOrganizer's recommendation agent. The user describes the plant they are looking
            for. First fill parsedConstraints with what they asked for: maxCareLevel ("Easy", "Medium",
            "Hard" or "Any"), petSafeOnly (true only if they mention pets/cats/dogs/kids safety),
            wateringPreference ("Minimum", "Average", "Frequent" or "Any") and light (their words).
            Then suggest plants that satisfy ALL constraints. Use their spaces to pick a recommendedSpace.
            Each reason must say how the plant meets the constraints. careLevel is "Easy", "Medium" or
            "Hard"; petSafe is true only if the plant is non-toxic to cats and dogs.
            """;

    private final CandidateGenerator generator;

    public UserConstraintStrategy(CandidateGenerator generator) {
        this.generator = generator;
    }

    @Override
    public String name() {
        return "User constraints";
    }

    @Override
    public List<RecommendedPlant> recommend(long userId, RecommendationContext ctx) {
        String prompt = CandidateGenerator.describe(ctx) + "\nThe user is looking for: \"" + ctx.constraints()
                + "\"\nSuggest " + ctx.count() + " plants.";
        JsonNode json = generator.ask(INSTRUCTIONS, prompt);
        JsonNode constraints = json.path("parsedConstraints");
        List<RecommendedPlant> plants = generator.read(userId, json, ctx).stream()
                .filter(p -> satisfies(p, constraints))
                .toList();
        generator.verify(plants);
        return plants;
    }

    /** Deterministic check of one candidate against the interpreted constraints. Package-private for tests. */
    static boolean satisfies(RecommendedPlant plant, JsonNode constraints) {
        String maxCare = lower(JsonValues.text(constraints.path("maxCareLevel")));
        if (maxCare.contains("easy") && plant.getCareRank() > 1) {
            return false;
        }
        if (maxCare.contains("medium") && plant.getCareRank() > 2) {
            return false;
        }
        if (Boolean.TRUE.equals(JsonValues.bool(constraints.path("petSafeOnly")))
                && !Boolean.TRUE.equals(plant.getPetSafe())) {
            return false;
        }
        String watering = lower(JsonValues.text(constraints.path("wateringPreference")));
        return !(watering.contains("minimum") && lower(plant.getWatering()).contains("frequent"));
    }

    private static String lower(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT);
    }
}
