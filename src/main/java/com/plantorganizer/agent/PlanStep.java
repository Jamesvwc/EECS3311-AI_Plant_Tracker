package com.plantorganizer.agent;

import java.util.Map;
import java.util.function.Function;

import com.plantorganizer.agent.tools.ToolType;

/**
 * One step of an agent plan: which tool to run and how to build its
 * arguments. The arguments are a function of the earlier steps' results, so a
 * plan can chain tools (look up care info, then save the plant with it, then
 * schedule it). A function that returns null skips the step. An optional step
 * may fail without failing the whole plan.
 */
public record PlanStep(ToolType tool, Function<StepResults, Map<String, Object>> args, boolean optional) {

    public static PlanStep of(ToolType tool, Map<String, Object> args) {
        return new PlanStep(tool, results -> args, false);
    }

    public static PlanStep then(ToolType tool, Function<StepResults, Map<String, Object>> args) {
        return new PlanStep(tool, args, false);
    }

    public static PlanStep optional(ToolType tool, Map<String, Object> args) {
        return new PlanStep(tool, results -> args, true);
    }
}
