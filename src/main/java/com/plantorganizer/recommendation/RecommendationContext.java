package com.plantorganizer.recommendation;

import java.util.List;

import com.plantorganizer.memory.UserPreferenceModel;

/**
 * The inputs handed to a recommendation strategy (Stage 1
 * RecommendationContext): what the user owns, the spaces they have, how often
 * they can water, what the agent remembers about their taste, and (F07 only)
 * the constraints they typed.
 *
 * @param exclude names that must not be recommended again (owned, wishlist, already listed)
 */
public record RecommendationContext(
        List<String> ownedPlants,
        List<String> spaces,
        String wateringHabit,
        UserPreferenceModel preferences,
        String constraints,
        List<String> exclude,
        int count) {
}
