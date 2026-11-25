package com.plantorganizer.cli;

import java.util.ArrayList;
import java.util.List;

/**
 * Splits one typed line into arguments, the job a shell does for a program's
 * command line. Whitespace separates arguments; text in "double" or 'single'
 * quotes stays together, so {@code /plants note P3 "repotted today"} gives
 * {@code [plants, note, P3, repotted today]}. Inside or outside quotes,
 * {@code \"}, {@code \'} and {@code \\} stand for the character itself; any
 * other backslash is kept, so Windows paths work unquoted.
 */
public final class CommandLineSplitter {

    /** One argument: its text (quotes removed) and where it starts and ends in the typed line. */
    public record Word(String text, int start, int end) {
    }

    private CommandLineSplitter() {
    }

    /** @throws IllegalArgumentException when a quote is never closed */
    public static List<String> split(String line) {
        return scan(line, true).stream().map(Word::text).toList();
    }

    /**
     * The arguments with their positions, for tab completion while a line is
     * still being typed: a quote that isn't closed yet runs to the end of the line.
     */
    public static List<Word> words(String line) {
        return scan(line, false);
    }

    private static List<Word> scan(String line, boolean strict) {
        List<Word> words = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int start = -1; // where the current argument starts, or -1 between arguments
        char quote = 0;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '\\' && i + 1 < line.length() && "\"'\\".indexOf(line.charAt(i + 1)) >= 0) {
                start = start < 0 ? i : start;
                current.append(line.charAt(++i));
            } else if (quote != 0) {
                if (c == quote) {
                    quote = 0;
                } else {
                    current.append(c);
                }
            } else if (c == '"' || c == '\'') {
                quote = c;
                start = start < 0 ? i : start;
            } else if (Character.isWhitespace(c)) {
                if (start >= 0) {
                    words.add(new Word(current.toString(), start, i));
                    current.setLength(0);
                    start = -1;
                }
            } else {
                start = start < 0 ? i : start;
                current.append(c);
            }
        }
        if (quote != 0 && strict) {
            throw new IllegalArgumentException("A " + (quote == '"' ? "double" : "single")
                    + " quote (" + quote + ") is never closed.");
        }
        if (start >= 0) {
            words.add(new Word(current.toString(), start, line.length()));
        }
        return words;
    }

    /** Puts a value in double quotes so {@link #split} reads it back as one argument. */
    public static String quote(String value) {
        return quote(value, '"', true);
    }

    /**
     * Puts a value in the given quotes, escaping that quote and backslashes so
     * {@link #split} reads it back unchanged. Without {@code close} the closing
     * quote is left off, for a value the user is still typing.
     */
    public static String quote(String value, char quote, boolean close) {
        String body = value.replace("\\", "\\\\").replace(String.valueOf(quote), "\\" + quote);
        return quote + body + (close ? String.valueOf(quote) : "");
    }
}
