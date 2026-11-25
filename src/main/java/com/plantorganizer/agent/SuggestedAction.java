package com.plantorganizer.agent;

/**
 * What the agent understood a message to ask for, in a fixed shape a front end
 * can act on without reading the reply text: the planner's intent plus the
 * arguments it extracted. Any argument may be null.
 *
 * The read-only chat used by the terminal CLI (Agent.jar) never performs a
 * change itself. When a message asks for one, the reply carries this
 * suggestion and the CLI turns it into the matching structured command
 * (e.g. MARK_WATERED + "fern" becomes {@code /plants water "fern"}), which the
 * user confirms before it runs. The server never needs to know the CLI's
 * command syntax.
 *
 * @param intent     a {@link ChatIntent.Type} name, e.g. "ADD_OWNED"
 * @param plantName  the plant mentioned, as written
 * @param spaceName  the room or spot mentioned
 * @param details    the rest of the request (a description, constraints, availability text, a note)
 * @param readOnly   true when this intent only reads data, so read-only chat answered it directly
 */
public record SuggestedAction(String intent, String plantName, String spaceName, String details, boolean readOnly) {

    static SuggestedAction of(ChatIntent intent) {
        return new SuggestedAction(intent.type().name(), blankToNull(intent.plantName()), blankToNull(intent.spaceName()),
                blankToNull(intent.details()), ChatAgent.READ_ONLY_INTENTS.contains(intent.type()));
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
