package com.plantorganizer.cli;

import java.io.PrintWriter;
import java.util.Locale;

/**
 * The terminal the user types into (Stage 1 class diagram: CliConsole with
 * {@code readCommand()} and {@code print(response)}). It only reads lines and
 * prints text: each line is handed to the {@link CommandDispatcher}, which
 * parses it and runs the command against the agent, and the response comes
 * back here to be printed. It knows nothing about commands, HTTP or JSON.
 * Lines come from a {@link LineInput}, which in a real terminal also handles
 * the arrow keys, history and tab completion.
 */
public class CliConsole {

    private final LineInput in;
    private final PrintWriter out;
    /** A command typed as the answer to a yes/no question; it runs next. */
    private String pendingCommand;
    /** The input ended (end of input, or Ctrl+D / Ctrl+C on an empty line in a terminal): nothing more will be read. */
    private boolean ended;

    public CliConsole(LineInput in, PrintWriter out) {
        this.in = in;
        this.out = out;
    }

    /** Runs the read-dispatch-print loop until /quit, the end of input, or Ctrl+D / Ctrl+C on an empty line. */
    public void run(Session session, CommandDispatcher dispatcher) {
        while (!session.quitRequested()) {
            String line = readCommand(session.prompt());
            if (line == null) {
                break;
            }
            dispatcher.execute(line);
        }
        print("Goodbye!");
    }

    /** Shows the prompt and reads the next line the user types; null at the end of input. */
    public String readCommand(String prompt) {
        if (pendingCommand != null) {
            String command = pendingCommand;
            pendingCommand = null;
            print(prompt + command);
            return command;
        }
        return read(prompt, false);
    }

    /** Shows a response to the user. */
    public void print(String response) {
        out.println(response);
        out.flush();
    }

    /** A short "working on it" line before a slow step (AI calls can take several seconds). */
    public void status(String message) {
        print(message + "...");
    }

    /**
     * Asks a yes/no question; anything but "y" or "yes" (or no answer) is no.
     * Typing a /command instead counts as no, and that command runs next.
     * Ending the input here (Ctrl+D or Ctrl+C on an empty line) also counts as no, and the
     * console then quits.
     */
    public boolean confirm(String question) {
        String answer = read(question + " [y/N] ", true);
        if (answer == null) {
            return false;
        }
        String a = answer.strip().toLowerCase(Locale.ROOT);
        if (a.startsWith("/")) {
            pendingCommand = answer.strip();
            return false;
        }
        return a.equals("y") || a.equals("yes");
    }

    public PrintWriter writer() {
        return out;
    }

    private String read(String prompt, boolean answer) {
        if (ended) {
            return null;
        }
        out.flush();
        String line = answer ? in.readAnswer(prompt) : in.readLine(prompt);
        ended = line == null;
        return line;
    }
}
