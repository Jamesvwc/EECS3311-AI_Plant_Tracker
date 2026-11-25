package com.plantorganizer.cli;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintWriter;

/**
 * Where {@link CliConsole} gets the lines the user types. In a real terminal
 * that is {@link TerminalInput} (JLine: arrow keys, history, tab completion);
 * otherwise, for piped input and the tests, plain lines from a reader.
 */
public interface LineInput extends AutoCloseable {

    /**
     * Shows the prompt and returns the line the user types. Returns null when
     * the input ends, or in a terminal when the user presses Ctrl+D or Ctrl+C
     * on an empty line: the console then quits as for /quit. Either way the
     * cursor is left at the start of a new line.
     */
    String readLine(String prompt);

    /** Like {@link #readLine}, for the answer to a question: it isn't kept in the command history. */
    default String readAnswer(String prompt) {
        return readLine(prompt);
    }

    @Override
    default void close() {
    }

    /** Plain lines from a reader, with the prompt written to {@code out}. */
    static LineInput plain(BufferedReader in, PrintWriter out) {
        return prompt -> {
            out.print(prompt);
            out.flush();
            String line;
            try {
                line = in.readLine();
            } catch (IOException e) {
                line = null;
            }
            if (line == null) {
                out.println(); // the cursor is still on the prompt's line
                out.flush();
            }
            return line;
        };
    }
}
