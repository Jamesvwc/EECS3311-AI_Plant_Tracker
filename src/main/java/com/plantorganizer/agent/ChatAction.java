package com.plantorganizer.agent;

import java.util.Map;

/**
 * A button under an agent reply in the chat box. SEND re-sends {@code payload.text}
 * as a chat message, OPEN navigates to {@code payload.url}; every other type is
 * sent back to /api/chat/action and handled by ChatAgent.act().
 */
public record ChatAction(String label, String type, Map<String, Object> payload) {

    public ChatAction {
        payload = payload == null ? Map.of() : Map.copyOf(payload);
    }

    public static ChatAction send(String label, String text) {
        return new ChatAction(label, "SEND", Map.of("text", text));
    }

    public static ChatAction open(String label, String url) {
        return new ChatAction(label, "OPEN", Map.of("url", url));
    }
}
