package com.plantorganizer.cli;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Turns the server's answers into plain terminal text. The agent writes for
 * the GUI chat box (**bold**, "- " bullets, _italics_); here those marks are
 * removed or replaced with plain-text equivalents. Output stays ASCII where
 * the CLI writes it itself, so it reads the same on every terminal.
 */
public final class Text {

    public static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("EEE MMM d, HH:mm", Locale.ENGLISH);
    public static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("EEE MMM d", Locale.ENGLISH);

    private Text() {
    }

    /** Agent text without chat-box formatting: no **bold** or _italics_; bullets indented. */
    public static String plain(String markdown) {
        if (markdown == null) {
            return "";
        }
        String s = markdown.replace("\r", "");
        s = s.replaceAll("\\*\\*(.+?)\\*\\*", "$1");
        s = s.replaceAll("(?m)^_(.+)_\\s*$", "$1");
        s = s.replaceAll("(?m)^#{1,6}\\s+", "");
        s = s.replaceAll("(?m)^\\s*[-*\\u2022]\\s+", "  - ");
        return s.strip();
    }

    /** Lines of a newline-separated list (as the server stores issues and care steps) as "  - item" bullets. */
    public static String bullets(String lines) {
        if (lines == null || lines.isBlank()) {
            return "";
        }
        List<String> out = new ArrayList<>();
        for (String line : lines.split("\n")) {
            String t = line.strip().replaceFirst("^[-*\\u2022]\\s*", "");
            if (!t.isEmpty()) {
                out.add("  - " + t);
            }
        }
        return String.join("\n", out);
    }

    /** "2026-11-24T18:00:00" (or an Instant) as "Tue Nov 24, 18:00"; "-" when missing. */
    public static String when(String iso) {
        if (iso == null || iso.isBlank()) {
            return "-";
        }
        try {
            return LocalDateTime.parse(iso).format(WHEN);
        } catch (DateTimeParseException e) {
            try {
                return Instant.parse(iso).atZone(ZoneId.systemDefault()).format(WHEN);
            } catch (DateTimeParseException e2) {
                return iso;
            }
        }
    }

    /** The calendar day of an ISO date-time, or null. */
    public static LocalDate day(String iso) {
        try {
            return iso == null ? null : LocalDateTime.parse(iso).toLocalDate();
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /** 0.9 as "90%". */
    public static String percent(double score) {
        return Math.round(score * 100) + "%";
    }

    /** An enum name such as MINOR_ISSUES as "minor issues". */
    public static String words(String enumName) {
        return enumName == null ? "unknown" : enumName.replace('_', ' ').toLowerCase(Locale.ROOT);
    }

    public static String orUnknown(String value) {
        return value == null || value.isBlank() ? "Unknown" : value;
    }

    /** Prefixes every line, e.g. to indent a block of agent text. */
    public static String indent(String text, String prefix) {
        return prefix + text.replace("\n", "\n" + prefix);
    }

    /** Rows as left-aligned columns separated by two spaces. */
    public static String table(List<String[]> rows, String indent) {
        int columns = rows.stream().mapToInt(r -> r.length).max().orElse(0);
        int[] widths = new int[columns];
        for (String[] row : rows) {
            for (int i = 0; i < row.length; i++) {
                widths[i] = Math.max(widths[i], row[i] == null ? 0 : row[i].length());
            }
        }
        StringBuilder sb = new StringBuilder();
        for (String[] row : rows) {
            StringBuilder line = new StringBuilder(indent);
            for (int i = 0; i < row.length; i++) {
                String cell = row[i] == null ? "" : row[i];
                line.append(i == row.length - 1 ? cell : String.format("%-" + widths[i] + "s  ", cell));
            }
            sb.append(line.toString().stripTrailing()).append('\n');
        }
        return sb.toString().stripTrailing();
    }

    /** "1 plant" / "2 plants". */
    public static String count(int n, String noun) {
        return n + " " + noun + (n == 1 ? "" : "s");
    }
}
