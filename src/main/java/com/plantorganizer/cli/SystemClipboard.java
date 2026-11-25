package com.plantorganizer.cli;

import java.awt.GraphicsEnvironment;
import java.awt.Toolkit;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * The computer's clipboard. On macOS it uses the pbcopy and pbpaste commands
 * (Java's own clipboard would start the desktop toolkit and put a Java icon
 * in the Dock); on Windows and Linux it uses Java's clipboard. Where there is
 * no clipboard to reach (no desktop, e.g. over SSH), what was copied is kept
 * here, so Ctrl+V at the prompt still pastes it.
 */
final class SystemClipboard implements TextClipboard {

    private static final long COMMAND_TIMEOUT_SECONDS = 3;

    private final List<String> copyCommand;  // null: use Java's clipboard
    private final List<String> pasteCommand;
    private String lastCopied = "";

    SystemClipboard(List<String> copyCommand, List<String> pasteCommand) {
        this.copyCommand = copyCommand;
        this.pasteCommand = pasteCommand;
    }

    static SystemClipboard forThisComputer() {
        boolean mac = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac");
        return mac ? new SystemClipboard(List.of("pbcopy"), List.of("pbpaste")) : new SystemClipboard(null, null);
    }

    @Override
    public void copy(String text) {
        lastCopied = text;
        try {
            if (copyCommand != null) {
                run(copyCommand, text);
            } else {
                javaClipboard().setContents(new StringSelection(text), null);
            }
        } catch (IOException | RuntimeException | LinkageError e) {
            // no clipboard to reach: Ctrl+V here still pastes lastCopied
        }
    }

    @Override
    public String paste() {
        try {
            String text = pasteCommand != null ? run(pasteCommand, null)
                    : (String) javaClipboard().getData(DataFlavor.stringFlavor);
            return text == null ? "" : text;
        } catch (UnsupportedFlavorException e) {
            return ""; // the clipboard holds something other than text, e.g. an image
        } catch (IOException | RuntimeException | LinkageError e) {
            return lastCopied;
        }
    }

    private static java.awt.datatransfer.Clipboard javaClipboard() throws IOException {
        if (GraphicsEnvironment.isHeadless()) {
            throw new IOException("No desktop, so no clipboard.");
        }
        return Toolkit.getDefaultToolkit().getSystemClipboard();
    }

    /** Runs a clipboard command, giving it {@code input} (if any); returns what it printed. */
    private static String run(List<String> command, String input) throws IOException {
        ProcessBuilder builder = new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD);
        builder.environment().put("LANG", "en_US.UTF-8"); // pbcopy and pbpaste read and write text in this encoding
        Process process = builder.start();
        try (OutputStream in = process.getOutputStream()) {
            if (input != null) {
                in.write(input.getBytes(StandardCharsets.UTF_8));
            }
        }
        String output;
        try (InputStream out = process.getInputStream()) {
            output = new String(out.readAllBytes(), StandardCharsets.UTF_8);
        }
        try {
            if (!process.waitFor(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IOException(command.get(0) + " didn't finish.");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted.", e);
        }
        if (process.exitValue() != 0) {
            throw new IOException(command.get(0) + " failed.");
        }
        return output;
    }
}
