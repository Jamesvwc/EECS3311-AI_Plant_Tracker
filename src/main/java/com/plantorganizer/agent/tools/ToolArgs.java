package com.plantorganizer.agent.tools;

import java.util.Map;

/** Typed reading of a tool's argument map, with clear errors for missing arguments. */
final class ToolArgs {

    private ToolArgs() {
    }

    static <T> T required(Map<String, Object> args, String key, Class<T> type) {
        Object value = args.get(key);
        if (value == null) {
            throw new IllegalArgumentException("Tool argument \"" + key + "\" is required");
        }
        return type.cast(value);
    }

    static <T> T optional(Map<String, Object> args, String key, Class<T> type) {
        Object value = args.get(key);
        return value == null ? null : type.cast(value);
    }

    static long userId(Map<String, Object> args) {
        return required(args, "userId", Long.class);
    }

    static String action(Map<String, Object> args) {
        return required(args, "action", String.class);
    }
}
