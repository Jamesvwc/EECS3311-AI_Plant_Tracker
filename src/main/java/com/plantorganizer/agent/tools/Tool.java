package com.plantorganizer.agent.tools;

import java.util.Map;

/**
 * Product interface of the Factory Method pattern: one capability the agent
 * can invoke by name with a map of arguments (the same shape an LLM uses when
 * it calls a tool). ToolFactory creates the concrete tools; ToolManager only
 * ever sees this interface.
 */
public interface Tool {

    ToolType type();

    /**
     * Runs the tool.
     *
     * @param args named arguments; each tool documents the keys it expects
     * @return the tool's result object (documented per tool)
     */
    Object execute(Map<String, Object> args);
}
