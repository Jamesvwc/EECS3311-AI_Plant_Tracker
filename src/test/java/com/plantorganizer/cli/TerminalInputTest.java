package com.plantorganizer.cli;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.jline.reader.Candidate;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The prompt's line editing, driven with the bytes a terminal sends for each
 * key (an emulated xterm, so no real terminal is needed).
 */
class TerminalInputTest {

    private static final String LEFT = "\033OD";
    private static final String RIGHT = "\033OC";
    private static final String UP = "\033OA";
    private static final String DOWN = "\033OB";
    private static final String CTRL_A = "\001";
    private static final String CTRL_C = "\003";
    private static final String CTRL_D = "\004";
    private static final String CTRL_V = "\026";
    private static final String BACKSPACE = "\177";
    private static final String TAB = "\t";
    private static final String ENTER = "\r";

    @TempDir
    Path dir;

    private ByteArrayOutputStream screen;
    private final TextClipboard clipboard = TextClipboard.inMemory();

    /** A prompt whose user types {@code keys}. */
    private TerminalInput typing(String keys) throws IOException {
        return prompt(new ByteArrayInputStream(keys.getBytes(UTF_8)));
    }

    private TerminalInput prompt(InputStream keys) throws IOException {
        screen = new ByteArrayOutputStream();
        // "exec" gives JLine's own emulated terminal; the native provider would open a real pseudo-terminal
        Terminal terminal = TerminalBuilder.builder().system(false).provider("exec").type("xterm-256color")
                .encoding(UTF_8).streams(keys, screen).build();
        return new TerminalInput(terminal, dir.resolve("history"), clipboard);
    }

    /**
     * Keys typed while the prompt is already reading in the background, for
     * checking what the prompt shows, and for Ctrl+C (which only reaches the
     * prompt as a key while it is reading; otherwise the terminal turns it
     * into its "stop" signal).
     */
    private final class LiveTyping {

        private final PipedOutputStream keys = new PipedOutputStream();
        private final TerminalInput in;
        private CompletableFuture<String> line;
        private int shown; // how much of the screen was drawn before this line

        LiveTyping() throws IOException {
            in = prompt(new PipedInputStream(keys));
            next();
        }

        /** Starts reading the next line, once the prompt is ready for keys. */
        LiveTyping next() {
            shown = screen.toString(UTF_8).length();
            line = CompletableFuture.supplyAsync(() -> in.readLine("> "));
            awaitScreen("\033[?1h"); // the prompt switched the keys to its mode: it is reading
            return this;
        }

        LiveTyping type(String text) throws IOException {
            keys.write(text.getBytes(UTF_8));
            keys.flush();
            return this;
        }

        /** Waits (up to 5 seconds) until the prompt has drawn {@code text}; false if it never did. */
        boolean awaitScreen(String text) {
            for (int i = 0; i < 100; i++) {
                if (screen.toString(UTF_8).substring(shown).contains(text)) {
                    return true;
                }
                try {
                    Thread.sleep(50);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
            return false;
        }

        String line() throws Exception {
            return line.get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void leftAndRightMoveTheCursorWithinTheLine() throws IOException {
        TerminalInput in = typing("/pants" + LEFT.repeat(4) + "l" + RIGHT.repeat(4) + " list" + ENTER);
        assertThat(in.readLine("> ")).isEqualTo("/plants list");
    }

    @Test
    void arrowKeysWorkInBothFormsTerminalsSend() throws IOException {
        TerminalInput in = typing("/pants" + "\033[D".repeat(4) + "l" + "\033[C".repeat(4) + " list" + ENTER
                + "/help" + "\033[H" + "\033[C" + "\033[F" + ENTER
                + "/users" + "\033[A" + ENTER);
        assertThat(in.readLine("> ")).isEqualTo("/plants list");
        assertThat(in.readLine("> ")).isEqualTo("/help");
        assertThat(in.readLine("> ")).isEqualTo("/help"); // Up replaces what was typed with the last command
    }

    @Test
    void ctrlAHighlightsTheWholeLineAndTheNextKeyActsOnAllOfIt() throws Exception {
        LiveTyping live = new LiveTyping().type("/plants lsit" + CTRL_A);
        assertThat(live.awaitScreen("\033[7m/plants lsit")).as("shown in reverse video").isTrue();
        assertThat(live.type("/help" + ENTER).line()).isEqualTo("/help"); // typing replaces it

        TerminalInput in = typing(
                "/plants" + CTRL_A + BACKSPACE + "/quit" + ENTER    // Backspace clears it
                + "plants" + CTRL_A + LEFT + "/" + ENTER            // Left goes to its start
                + "/plants" + CTRL_A + RIGHT + " list" + ENTER      // Right goes to its end
                + CTRL_A + "/help" + ENTER);                        // nothing to select on an empty line
        assertThat(in.readLine("> ")).isEqualTo("/quit");
        assertThat(in.readLine("> ")).isEqualTo("/plants");
        assertThat(in.readLine("> ")).isEqualTo("/plants list");
        assertThat(in.readLine("> ")).isEqualTo("/help");
    }

    @Test
    void upAndDownGoThroughEarlierCommandsWhichAreSavedForNextTime() throws IOException {
        TerminalInput in = typing("/plants list" + ENTER + "/help" + ENTER + "y" + ENTER
                + UP + UP + ENTER + UP + UP + DOWN + ENTER + "/users list" + ENTER);
        assertThat(in.readLine("> ")).isEqualTo("/plants list");
        assertThat(in.readLine("> ")).isEqualTo("/help");
        assertThat(in.readAnswer("Sure? [y/N] ")).isEqualTo("y");
        assertThat(in.readLine("> ")).isEqualTo("/plants list"); // the answer "y" isn't in the history
        assertThat(in.readLine("> ")).isEqualTo("/plants list");
        assertThat(in.readAnswer("Sure? [y/N] ")).isEqualTo("/users list"); // a command typed as an answer is kept
        in.close();

        String saved = Files.readString(dir.resolve("history"));
        assertThat(saved).contains("/plants list", "/help", "/users list").doesNotContain(":y\n");
        TerminalInput nextTime = typing(UP + ENTER);
        assertThat(nextTime.readLine("> ")).isEqualTo("/users list");
    }

    @Test
    void ctrlCCopiesTheHighlightedLineAndCtrlVPastesIt() throws Exception {
        LiveTyping live = new LiveTyping();
        assertThat(live.type("/plants water Monty" + CTRL_A + CTRL_C + RIGHT + ENTER).line())
                .isEqualTo("/plants water Monty"); // Ctrl+C copied it and left it as it was
        assertThat(clipboard.paste()).isEqualTo("/plants water Monty");

        assertThat(live.next().type(CTRL_V + ENTER).line()).isEqualTo("/plants water Monty");
        assertThat(live.next().type("/help" + CTRL_A + CTRL_V + ENTER).line())
                .isEqualTo("/plants water Monty"); // pasting replaces the highlighted line
        assertThat(live.next().type("/help" + CTRL_C + ENTER).line())
                .isEqualTo("/help"); // not highlighted: Ctrl+C does nothing (and doesn't quit)
        assertThat(clipboard.paste()).isEqualTo("/plants water Monty");

        clipboard.copy("/plants note P3\r\n\"repotted today\"\n"); // copied from somewhere else, over two lines
        assertThat(live.next().type(CTRL_V + ENTER).line()).isEqualTo("/plants note P3 \"repotted today\"");
    }

    @Test
    void ctrlCOnAnEmptyLineQuits() throws Exception {
        assertThat(new LiveTyping().type(CTRL_C).line()).isNull();
        assertThat(new LiveTyping().type("/help" + CTRL_A + BACKSPACE + CTRL_C).line()).isNull();
    }

    @Test
    void ctrlDOnAnEmptyLineEndsTheInput() throws IOException {
        assertThat(typing(CTRL_D).readLine("> ")).isNull();
        assertThat(typing("/help" + CTRL_D + ENTER).readLine("> ")).isEqualTo("/help"); // not empty: nothing happens
        assertThat(typing("/help" + CTRL_A + CTRL_D + CTRL_D).readLine("> ")).isNull(); // clears, then ends
    }

    @Test
    void theLineComesBackExactlyAsTyped() throws IOException {
        String line = "/chat \"is it ok?! \\\"yes\\\"\" C:\\photos\\fern.jpg";
        assertThat(typing(line + ENTER).readLine("> ")).isEqualTo(line);
    }

    @Test
    void tabCompletesAndPutsValuesWithSpacesInQuotes() throws IOException {
        TerminalInput in = typing("/plants water Mons" + TAB + ENTER
                + "/plants water \"monst" + TAB + ENTER
                + "/plants water Mo" + TAB + "t" + TAB + ENTER);
        in.completeWith((reader, line, candidates) ->
                List.of("Monstera deliciosa", "Monty").forEach(v -> candidates.add(new Candidate(v))));
        assertThat(in.readLine("> ")).isEqualTo("/plants water \"Monstera deliciosa\" ");
        assertThat(in.readLine("> ")).isEqualTo("/plants water \"Monstera deliciosa\" ");
        assertThat(in.readLine("> ")).isEqualTo("/plants water Monty ");
        assertThat(CommandLineSplitter.split("/plants water \"Monstera deliciosa\" ")).containsExactly(
                "/plants", "water", "Monstera deliciosa");
    }
}
