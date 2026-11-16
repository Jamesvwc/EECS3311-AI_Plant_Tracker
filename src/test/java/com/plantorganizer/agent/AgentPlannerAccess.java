package com.plantorganizer.agent;

import java.util.List;

import com.plantorganizer.plants.DesiredPlant.FitVerdict;
import com.plantorganizer.spaces.LightLevel;

/** Test-only bridge to the agent's package-private deterministic rules. */
public final class AgentPlannerAccess {

    private AgentPlannerAccess() {
    }

    public static FitVerdict fit(List<String> sunlight, LightLevel light) {
        return PlantAgent.ruleBasedFit(sunlight, light);
    }

    public static ChatIntent.Type keywords(String message, boolean photo) {
        return AgentPlanner.keywordIntent(message, photo);
    }
}
