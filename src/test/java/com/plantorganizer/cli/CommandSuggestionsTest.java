package com.plantorganizer.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** How the agent's structured suggestions become commands (no plants involved, so no server is needed). */
class CommandSuggestionsTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static JsonNode suggestion(Map<String, String> fields) {
        return JSON.valueToTree(fields);
    }

    @Test
    void intentsWithTheirValuesBecomeCommands() {
        assertThat(CommandSuggestions.toCommand(suggestion(Map.of("intent", "IDENTIFY", "details", "big leaves with holes")), null))
                .isEqualTo("/identify text \"big leaves with holes\"");
        assertThat(CommandSuggestions.toCommand(suggestion(Map.of("intent", "ADD_OWNED", "plantName", "Ficus lyrata")), null))
                .isEqualTo("/plants add \"Ficus lyrata\"");
        assertThat(CommandSuggestions.toCommand(suggestion(Map.of("intent", "RECOMMEND", "details", "safe for cats")), null))
                .isEqualTo("/recommend ask \"safe for cats\"");
        assertThat(CommandSuggestions.toCommand(suggestion(Map.of("intent", "UNDO_SCHEDULE")), null)).isEqualTo("/calendar undo");
    }

    @Test
    void questionsAndMissingValuesGiveNoCommand() {
        assertThat(CommandSuggestions.toCommand(suggestion(Map.of("intent", "GENERAL")), null)).isNull();
        JsonNode noPlant = suggestion(Map.of("intent", "ADD_OWNED"));
        assertThat(CommandSuggestions.toCommand(noPlant, null)).isNull();
        assertThat(CommandSuggestions.template(noPlant)).isEqualTo("/plants add \"<scientific name>\"");
    }

    @Test
    void aSuggestionThatOnlyEchoesTheTypedLineIsIgnored() {
        // without an AI key the server's keyword fallback returns the whole message as "details"
        assertThat(CommandDispatcher.echoesInput(suggestion(Map.of("intent", "IDENTIFY", "details", "/identify photo")),
                "/identify photo")).isTrue();
        assertThat(CommandDispatcher.echoesInput(suggestion(Map.of("intent", "ADD_NOTE", "plantName", "monty",
                "details", "repotted today")), "/plants note monty repotted today")).isFalse();
    }
}
