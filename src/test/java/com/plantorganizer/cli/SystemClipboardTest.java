package com.plantorganizer.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The clipboard behind Ctrl+C / Ctrl+V, with stand-in commands so the real clipboard is left alone. */
class SystemClipboardTest {

    @TempDir
    Path dir;

    @Test
    void copiesAndPastesThroughTheClipboardCommands() throws IOException {
        assumeFalse(System.getProperty("os.name", "").startsWith("Windows"), "uses sh and cat");
        Path file = dir.resolve("clipboard");
        SystemClipboard clipboard = new SystemClipboard(List.of("sh", "-c", "cat > \"$0\"", file.toString()),
                List.of("cat", file.toString()));
        String text = "/plants add \"Monstera deliciosa\" --nickname Mönty";
        clipboard.copy(text);
        assertThat(Files.readString(file)).isEqualTo(text);
        assertThat(clipboard.paste()).isEqualTo(text);
    }

    @Test
    void withoutAClipboardWhatWasCopiedHereStillPastes() {
        SystemClipboard none = new SystemClipboard(List.of("no-such-clipboard-command"), List.of("no-such-clipboard-command"));
        assertThat(none.paste()).isEmpty();
        none.copy("/plants list");
        assertThat(none.paste()).isEqualTo("/plants list");
    }
}
