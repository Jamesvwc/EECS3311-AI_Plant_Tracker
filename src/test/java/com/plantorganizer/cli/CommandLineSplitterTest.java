package com.plantorganizer.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class CommandLineSplitterTest {

    @Test
    void quotesKeepMultiWordValuesTogether() {
        assertThat(CommandLineSplitter.split("plants note P3 \"repotted today\""))
                .containsExactly("plants", "note", "P3", "repotted today");
        assertThat(CommandLineSplitter.split("spaces place 'Living room'   \"Window sill\" Monty"))
                .containsExactly("spaces", "place", "Living room", "Window sill", "Monty");
        assertThat(CommandLineSplitter.split("plants rename P3 \"\"")).containsExactly("plants", "rename", "P3", "");
    }

    @Test
    void escapesAndWindowsPaths() {
        assertThat(CommandLineSplitter.split("chat \"she said \\\"hi\\\"\"")).containsExactly("chat", "she said \"hi\"");
        assertThat(CommandLineSplitter.split("identify photo C:\\photos\\fern.jpg"))
                .containsExactly("identify", "photo", "C:\\photos\\fern.jpg");
    }

    @Test
    void anUnclosedQuoteIsAnError() {
        assertThatThrownBy(() -> CommandLineSplitter.split("plants add \"Monstera deliciosa"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("never closed");
    }

    @Test
    void wordsKeepTheirPositionsAndAQuoteMayStillBeOpen() {
        assertThat(CommandLineSplitter.words("/plants water \"Monstera del")).containsExactly(
                new CommandLineSplitter.Word("/plants", 0, 7),
                new CommandLineSplitter.Word("water", 8, 13),
                new CommandLineSplitter.Word("Monstera del", 14, 27));
        assertThat(CommandLineSplitter.words("  a  'b c'  ")).containsExactly(
                new CommandLineSplitter.Word("a", 2, 3), new CommandLineSplitter.Word("b c", 5, 10));
    }

    @Test
    void quoteRoundTrips() {
        String value = "a \"tricky\" \\ name";
        assertThat(CommandLineSplitter.split("x " + CommandLineSplitter.quote(value))).containsExactly("x", value);
        assertThat(CommandLineSplitter.split("x " + CommandLineSplitter.quote(value, '\'', true))).containsExactly("x", value);
        assertThat(CommandLineSplitter.quote("it's", '\'', false)).isEqualTo("'it\\'s");
    }
}
