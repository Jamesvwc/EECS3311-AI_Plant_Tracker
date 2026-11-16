package com.plantorganizer.recommendation;

import java.util.List;

import org.springframework.stereotype.Component;

/**
 * Concrete strategy (F06): recommends plants from what the agent has learned:
 * the plants the user owns, their spaces' light, how often they water, and
 * their recorded likes and dislikes.
 */
@Component
public class LearnedPreferenceStrategy implements RecommendationStrategy {

    private static final String INSTRUCTIONS = """
            You are PlantOrganizer's recommendation agent. Suggest house plants this user is likely to
            enjoy, based on patterns in what they own and like (families, leaf shapes, care level), the
            light in their spaces, and how often they can water. Prefer plants that suit one of their
            spaces and name that space in recommendedSpace. Each reason must say which pattern or
            space it is based on, in one or two sentences. careLevel is "Easy", "Medium" or "Hard".
            Never suggest a plant they rejected or already have.
            """;

    private final CandidateGenerator generator;

    public LearnedPreferenceStrategy(CandidateGenerator generator) {
        this.generator = generator;
    }

    @Override
    public String name() {
        return "Learned preferences";
    }

    @Override
    public List<RecommendedPlant> recommend(long userId, RecommendationContext ctx) {
        String prompt = CandidateGenerator.describe(ctx) + "\nSuggest " + ctx.count() + " plants.";
        List<RecommendedPlant> plants = generator.read(userId, generator.ask(INSTRUCTIONS, prompt), ctx);
        generator.verify(plants);
        return plants;
    }
}
