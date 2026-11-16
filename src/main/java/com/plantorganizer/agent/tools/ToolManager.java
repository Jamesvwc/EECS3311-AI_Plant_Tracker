package com.plantorganizer.agent.tools;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Holds the agent's tools and runs them by type. At start-up it asks
 * ToolFactory to create one instance of every ToolType and registers it.
 */
@Component
public class ToolManager {

    private static final Logger log = LoggerFactory.getLogger(ToolManager.class);

    private final Map<ToolType, Tool> tools = new EnumMap<>(ToolType.class);

    public ToolManager(ToolFactory toolFactory) {
        for (ToolType type : ToolType.values()) {
            registerTool(toolFactory.createTool(type));
        }
    }

    public void registerTool(Tool tool) {
        tools.put(tool.type(), tool);
    }

    public Set<ToolType> availableTools() {
        return Collections.unmodifiableSet(tools.keySet());
    }

    public Object executeTool(ToolType type, Map<String, Object> args) {
        Tool tool = tools.get(type);
        if (tool == null) {
            throw new IllegalStateException("No tool registered for " + type);
        }
        log.debug("Agent executing tool {}", type);
        return tool.execute(args);
    }
}
