package com.plantorganizer.recommendation;

import java.util.List;

/**
 * Strategy pattern (F06/F07). RecommendationEngine (the context) calls
 * {@link #recommend} on whichever strategy is set: LearnedPreferenceStrategy
 * for the automatic list, UserConstraintStrategy when the user states what
 * they want. Both return unsaved RecommendedPlant objects; the engine stores them.
 */
public interface RecommendationStrategy {

    String name();

    List<RecommendedPlant> recommend(long userId, RecommendationContext context);
}
