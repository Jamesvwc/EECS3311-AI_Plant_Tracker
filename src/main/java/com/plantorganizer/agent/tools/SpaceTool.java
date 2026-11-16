package com.plantorganizer.agent.tools;

import java.util.Map;

import com.plantorganizer.spaces.SpaceManager;

/**
 * Concrete tool (Stage 1 SpaceTool, F10): lets the agent place a plant when
 * the user says where it is, and gives the agent a description of the user's
 * spaces for placement advice (F01) and recommendations.
 *
 * Actions: assign, describe.
 */
public class SpaceTool implements Tool {

    private final SpaceManager spaceManager;

    SpaceTool(SpaceManager spaceManager) {
        this.spaceManager = spaceManager;
    }

    @Override
    public ToolType type() {
        return ToolType.SPACE;
    }

    @Override
    public Object execute(Map<String, Object> args) {
        long userId = ToolArgs.userId(args);
        return switch (ToolArgs.action(args)) {
            case "assign" -> spaceManager.assignPlant(userId, ToolArgs.required(args, "plantId", Long.class),
                    ToolArgs.required(args, "spaceId", Long.class));
            case "describe" -> spaceManager.describeSpaces(userId);
            default -> throw new IllegalArgumentException("Unknown space action");
        };
    }
}
