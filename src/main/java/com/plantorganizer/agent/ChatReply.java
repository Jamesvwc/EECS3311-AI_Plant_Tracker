package com.plantorganizer.agent;

import java.util.List;

/**
 * The agent's answer in the chat box (CLI): text (paragraphs, "- " bullets and
 * **bold**), optional buttons, which intent the planner chose and which tools
 * ran, so the agent's reasoning is visible.
 *
 * @param suggestion set by the read-only chat (Agent.jar's /chat) when the
 *                   message asked for a change: the structured action the
 *                   front end should offer to run instead; null otherwise
 */
public record ChatReply(String text, List<ChatAction> actions, String intent, List<String> toolsUsed,
                        SuggestedAction suggestion) {

    public ChatReply {
        actions = actions == null ? List.of() : List.copyOf(actions);
        toolsUsed = toolsUsed == null ? List.of() : List.copyOf(toolsUsed);
    }

    public ChatReply(String text, List<ChatAction> actions, String intent, List<String> toolsUsed) {
        this(text, actions, intent, toolsUsed, null);
    }

    public static ChatReply of(String text, String intent) {
        return new ChatReply(text, List.of(), intent, List.of());
    }

    /** The same reply without buttons (read-only chat: buttons would run changes). */
    ChatReply withoutActions() {
        return new ChatReply(text, List.of(), intent, toolsUsed, suggestion);
    }
}
