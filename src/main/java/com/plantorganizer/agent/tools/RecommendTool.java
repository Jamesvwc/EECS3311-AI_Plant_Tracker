package com.plantorganizer.agent.tools;

import java.util.Map;

import com.plantorganizer.recommendation.RecommendationEngine;

/**
 * Concrete tool (Stage 1 RecommendTool): bridges the agent to the
 * recommendation subsystem. Actions: learned (F06 refresh), constraints (F07).
 */
public class RecommendTool implements Tool {

    private final RecommendationEngine engine;

    RecommendTool(RecommendationEngine engine) {
        this.engine = engine;
    }

    @Override
    public ToolType type() {
        return ToolType.RECOMMEND;
    }

    @Override
    public Object execute(Map<String, Object> args) {
        long userId = ToolArgs.userId(args);
        return switch (ToolArgs.action(args)) {
            case "learned" -> engine.refresh(userId);
            case "constraints" -> engine.recommendFor(userId, ToolArgs.required(args, "constraints", String.class));
            default -> throw new IllegalArgumentException("Unknown recommend action");
        };
    }
}
