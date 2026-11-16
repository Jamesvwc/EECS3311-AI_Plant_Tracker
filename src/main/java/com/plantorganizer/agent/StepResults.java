package com.plantorganizer.agent;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import com.plantorganizer.agent.tools.ToolType;

/** The results of the plan steps run so far, by tool, plus notes about optional steps that failed. */
public final class StepResults {

    private final Map<ToolType, Object> results = new EnumMap<>(ToolType.class);
    private final List<String> notes = new ArrayList<>();
    private final List<String> toolsUsed = new ArrayList<>();
    private Object last;

    void put(ToolType tool, Object result) {
        results.put(tool, result);
        toolsUsed.add(tool.name());
        last = result;
    }

    /** The result of the last step that ran. */
    Object last() {
        return last;
    }

    List<String> toolsUsed() {
        return List.copyOf(toolsUsed);
    }

    void note(String note) {
        notes.add(note);
    }

    public <T> T get(ToolType tool, Class<T> type) {
        Object value = results.get(tool);
        return value == null ? null : type.cast(value);
    }

    public boolean has(ToolType tool) {
        return results.containsKey(tool);
    }

    public List<String> notes() {
        return List.copyOf(notes);
    }
}
