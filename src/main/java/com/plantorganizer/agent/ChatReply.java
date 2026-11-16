package com.plantorganizer.agent;

import java.util.List;

/**
 * The agent's answer in the chat box (CLI): text (paragraphs, "- " bullets and
 * **bold**), optional buttons, which intent the planner chose and which tools
 * ran, so the agent's reasoning is visible.
 */
public record ChatReply(String text, List<ChatAction> actions, String intent, List<String> toolsUsed) {

    public ChatReply {
        actions = actions == null ? List.of() : List.copyOf(actions);
        toolsUsed = toolsUsed == null ? List.of() : List.copyOf(toolsUsed);
    }

    public static ChatReply of(String text, String intent) {
        return new ChatReply(text, List.of(), intent, List.of());
    }
}
