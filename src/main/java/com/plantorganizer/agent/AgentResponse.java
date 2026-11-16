package com.plantorganizer.agent;

import java.util.List;

/**
 * What PlantAgent.run() returns: the final result (its type depends on the
 * request type, e.g. IdentificationResult, PlantReport, ScheduleResult),
 * notes about steps that were skipped or failed, and the tools that ran.
 */
public record AgentResponse(Object result, List<String> notes, List<String> toolsUsed) {

    public AgentResponse {
        notes = notes == null ? List.of() : List.copyOf(notes);
        toolsUsed = toolsUsed == null ? List.of() : List.copyOf(toolsUsed);
    }

    public <T> T resultAs(Class<T> type) {
        return type.cast(result);
    }
}
