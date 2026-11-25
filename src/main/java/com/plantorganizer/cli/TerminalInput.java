package com.plantorganizer.cli;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Set;

import org.jline.keymap.KeyMap;
import org.jline.reader.Binding;
import org.jline.reader.Completer;
import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.MaskingCallback;
import org.jline.reader.Reference;
import org.jline.reader.UserInterruptException;
import org.jline.reader.Widget;
import org.jline.reader.impl.LineReaderImpl;
import org.jline.reader.impl.history.DefaultHistory;
import org.jline.terminal.Attributes;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;

/**
 * The prompt in a real terminal, using JLine instead of reading raw lines:
 *
 *   - Left and Right move the cursor within the line; Home and End jump to
 *     its start and end (Ctrl+E also goes to the end);
 *   - Up and Down go through earlier commands, which are saved in
 *     {@code ~/.plant-organizer/agent_history} so they are still there next time;
 *   - Ctrl+A highlights the whole line: Ctrl+C copies it, typing replaces it,
 *     Backspace or Delete clears it, Left or Right puts the cursor at its start or end;
 *   - Ctrl+C copies the highlighted line to the computer's clipboard. On an
 *     empty line it quits like /quit; on a line that isn't highlighted it does nothing;
 *   - Ctrl+V pastes from the clipboard (replacing the highlighted line);
 *   - Tab completes commands, verbs, options and values (see {@link CommandCompleter});
 *   - Ctrl+D on an empty line quits like /quit.
 *
 * Without a terminal (input piped in, or the tests) {@link #open} returns
 * null and the console reads plain lines instead.
 */
public final class TerminalInput implements LineInput {

    static final Path HISTORY_FILE = Path.of(System.getProperty("user.home"), ".plant-organizer", "agent_history");

    private final Terminal terminal;
    private final EditingReader reader;

    /** Reads from {@code terminal}, keeping the command history in {@code historyFile}. */
    TerminalInput(Terminal terminal, Path historyFile, TextClipboard clipboard) {
        this.terminal = terminal;
        this.reader = new EditingReader(terminal, clipboard);
        reader.setParser(new CommandCompleter.Parser());
        reader.setHistory(new DefaultHistory());
        reader.setVariable(LineReader.HISTORY_FILE, historyFile);
        reader.setVariable(LineReader.HISTORY_SIZE, 500);
        reader.setVariable(LineReader.HISTORY_FILE_SIZE, 1000);
        // Keep the typed line exactly as typed: no "!" history expansion, no removing of backslashes.
        reader.option(LineReader.Option.DISABLE_EVENT_EXPANSION, true);
        reader.option(LineReader.Option.CASE_INSENSITIVE, true);
        reader.option(LineReader.Option.INSERT_TAB, false);
    }

    /**
     * Opens the terminal this program runs in; null when there isn't an
     * interactive one (input or output redirected), or JLine can't use it.
     */
    public static TerminalInput open() {
        try {
            Terminal terminal = TerminalBuilder.builder().system(true).dumb(true).build();
            if (terminal.getType().startsWith(Terminal.TYPE_DUMB)) {
                terminal.close();
                return null;
            }
            return new TerminalInput(terminal, HISTORY_FILE, SystemClipboard.forThisComputer());
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** Tab completion for the commands, once the session exists. */
    public void completeWith(Completer completer) {
        reader.setCompleter(completer);
    }

    /**
     * Ctrl+C while a command is running (not at the prompt, so there is
     * nothing to copy) quits: prints Goodbye and exits instead of stopping
     * halfway through silently.
     */
    public void quitOnInterrupt(PrintWriter out) {
        terminal.handle(Terminal.Signal.INT, signal -> {
            out.println();
            out.println("Goodbye!");
            out.flush();
            System.exit(0);
        });
    }

    @Override
    public String readLine(String prompt) {
        return read(prompt, false);
    }

    @Override
    public String readAnswer(String prompt) {
        String answer = read(prompt, true);
        if (answer != null && answer.strip().startsWith("/")) {
            reader.getHistory().add(answer.strip()); // a command typed instead of an answer is still a command
        }
        return answer;
    }

    private String read(String prompt, boolean answer) {
        // At the prompt Ctrl+C is a key (copy, or quit on an empty line), not the terminal's "stop" signal.
        Attributes before = terminal.getAttributes();
        Attributes keysOnly = new Attributes(before);
        keysOnly.setLocalFlag(Attributes.LocalFlag.ISIG, false);
        terminal.setAttributes(keysOnly);
        reader.setVariable(LineReader.DISABLE_HISTORY, answer);
        try {
            return reader.readLine(prompt);
        } catch (UserInterruptException | EndOfFileException e) {
            return null;
        } finally {
            reader.setVariable(LineReader.DISABLE_HISTORY, false);
            terminal.setAttributes(before);
        }
    }

    @Override
    public void close() {
        try {
            reader.getHistory().save();
        } catch (IOException | RuntimeException e) {
            // the history is a convenience: losing it is not worth an error message
        }
        try {
            terminal.close();
        } catch (IOException e) {
            // closing on the way out
        }
    }

    /**
     * JLine's line reader plus a text box's select-all, copy and paste:
     * Ctrl+A selects the whole line (shown in reverse video), Ctrl+C copies
     * it, Ctrl+V pastes. While the line is selected, the next key acts on all
     * of it; any other key just unselects it first.
     */
    static final class EditingReader extends LineReaderImpl {

        static final String SELECT_LINE = "select-line";
        static final String COPY_OR_QUIT = "copy-or-quit";
        static final String PASTE = "paste-clipboard";

        private static final Set<String> REPLACE = Set.of(SELF_INSERT, SELF_INSERT_UNMETA, QUOTED_INSERT, BEGIN_PASTE,
                YANK, PASTE);
        private static final Set<String> DELETE = Set.of(BACKWARD_DELETE_CHAR, VI_BACKWARD_DELETE_CHAR, DELETE_CHAR,
                DELETE_CHAR_OR_LIST, BACKWARD_KILL_WORD, BACKWARD_DELETE_WORD, KILL_WORD, DELETE_WORD, KILL_LINE,
                BACKWARD_KILL_LINE, KILL_WHOLE_LINE, KILL_BUFFER, KILL_REGION);
        private static final Set<String> TO_START = Set.of(BACKWARD_CHAR, BACKWARD_WORD, BEGINNING_OF_LINE);
        private static final Set<String> TO_END = Set.of(FORWARD_CHAR, FORWARD_WORD, END_OF_LINE);
        /** Keys that leave the selection as it is. */
        private static final Set<String> KEEP = Set.of(SELECT_LINE, COPY_OR_QUIT);

        private final TextClipboard clipboard;
        private boolean lineSelected;

        EditingReader(Terminal terminal, TextClipboard clipboard) {
            super(terminal, "plant-organizer-agent", new HashMap<>());
            this.clipboard = clipboard;
            getWidgets().put(SELECT_LINE, this::selectLine);
            getWidgets().put(COPY_OR_QUIT, this::copyOrQuit);
            getWidgets().put(PASTE, this::paste);
            for (String keymap : List.of(EMACS, MAIN)) {
                KeyMap<Binding> keys = getKeyMaps().get(keymap);
                keys.bind(new Reference(SELECT_LINE), KeyMap.ctrl('A'));
                keys.bind(new Reference(COPY_OR_QUIT), KeyMap.ctrl('C'));
                keys.bind(new Reference(PASTE), KeyMap.ctrl('V'));
                // Terminals send the arrow, Home and End keys as ESC O x or ESC [ x, depending on
                // their mode: bind both. Up and Down step through earlier commands.
                for (String form : List.of("\033O", "\033[")) {
                    keys.bind(new Reference(UP_LINE_OR_HISTORY), form + "A");
                    keys.bind(new Reference(DOWN_LINE_OR_HISTORY), form + "B");
                    keys.bind(new Reference(FORWARD_CHAR), form + "C");
                    keys.bind(new Reference(BACKWARD_CHAR), form + "D");
                    keys.bind(new Reference(BEGINNING_OF_LINE), form + "H");
                    keys.bind(new Reference(END_OF_LINE), form + "F");
                }
            }
        }

        @Override
        public String readLine(String prompt, String rightPrompt, MaskingCallback maskingCallback, String buffer) {
            lineSelected = false;
            return super.readLine(prompt, rightPrompt, maskingCallback, buffer);
        }

        private boolean selectLine() {
            if (getBuffer().length() == 0) {
                return true;
            }
            regionMark = 0;
            regionActive = RegionType.CHAR;
            getBuffer().cursor(getBuffer().length());
            lineSelected = true;
            return true;
        }

        /** Ctrl+C: copies the selected line; on an empty line, quits; otherwise does nothing. */
        private boolean copyOrQuit() {
            if (lineSelected) {
                clipboard.copy(getBuffer().toString());
            } else if (getBuffer().length() == 0) {
                state = State.INTERRUPT; // readLine ends, and the console quits as for /quit
            }
            return true;
        }

        /** Ctrl+V: types the clipboard's text, on one line (a command is one line). */
        private boolean paste() {
            String text = clipboard.paste().replaceAll("\\R+$", "").replaceAll("\\R", " ");
            getBuffer().write(text);
            return true;
        }

        private void unselect() {
            lineSelected = false;
            regionActive = RegionType.NONE;
        }

        @Override
        protected Widget getWidget(Object binding) {
            Widget widget = super.getWidget(binding);
            String name = binding instanceof Reference ref ? ref.name() : "";
            if (!lineSelected || KEEP.contains(name)) {
                return widget;
            }
            return () -> {
                unselect();
                if (REPLACE.contains(name)) {
                    getBuffer().clear();
                    return widget.apply();
                }
                if (DELETE.contains(name)) {
                    getBuffer().clear();
                    return true;
                }
                if (TO_START.contains(name) || TO_END.contains(name)) {
                    getBuffer().cursor(TO_START.contains(name) ? 0 : getBuffer().length());
                    return true;
                }
                return widget.apply();
            };
        }
    }
}
