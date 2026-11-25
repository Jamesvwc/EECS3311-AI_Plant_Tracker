package com.plantorganizer.cli;

import java.lang.reflect.Constructor;
import java.util.List;
import java.util.Locale;

import com.plantorganizer.cli.commands.Commands;

import picocli.CommandLine;
import picocli.CommandLine.ParameterException;
import picocli.CommandLine.UnmatchedArgumentException;
import tools.jackson.databind.JsonNode;

/**
 * Turns one typed line into a command and runs it. The rules every command
 * follows are enforced here:
 *
 *   - a command starts with "/"; anything else is "Unknown command";
 *   - the shape is {@code /noun verb <required values> --optional-things},
 *     parsed by picocli against the command classes in {@link Commands};
 *   - a line that doesn't fit its command (a missing value, an unquoted
 *     multi-word value, an unknown option) is reported with the command's
 *     usage, and the agent is asked what the user meant, offering the
 *     corrected command to run after confirmation;
 *   - errors from the server or from name matching are printed as messages.
 */
public final class CommandDispatcher {

    public static final String UNKNOWN = "Unknown command. Type /help for more info.";

    private final Session session;
    private final CommandLine.IFactory factory;
    private String currentLine;
    private boolean offerSuggestions;

    public CommandDispatcher(Session session) {
        this.session = session;
        this.factory = new SessionFactory(session);
        session.setDispatcher(this);
    }

    /** Runs one line typed by the user. */
    public void execute(String line) {
        execute(line, true);
    }

    /**
     * @param suggest whether a malformed line may be sent to the agent for a
     *                "did you mean" suggestion (false for a suggested command
     *                itself, so suggestions never chain)
     */
    void execute(String line, boolean suggest) {
        String trimmed = line.strip();
        if (trimmed.isEmpty()) {
            return;
        }
        if (!trimmed.startsWith("/")) {
            print(UNKNOWN);
            return;
        }
        List<String> args;
        try {
            args = CommandLineSplitter.split(trimmed.substring(1));
        } catch (IllegalArgumentException e) {
            print(e.getMessage() + " Put each value that has spaces in matching quotes, "
                    + "e.g. /plants note P3 \"repotted today\".");
            return;
        }
        Class<?> command = args.isEmpty() ? null : Commands.find(args.get(0).toLowerCase(Locale.ROOT));
        if (command == null) {
            print(UNKNOWN);
            if (suggest && !args.isEmpty()) {
                suggestCommand(trimmed);
            }
            return;
        }
        String previousLine = currentLine;
        boolean previousSuggest = offerSuggestions;
        currentLine = trimmed;
        offerSuggestions = suggest;
        try {
            commandLine(command).execute(args.subList(1, args.size()).toArray(String[]::new));
        } finally {
            currentLine = previousLine;
            offerSuggestions = previousSuggest;
        }
    }

    /** A picocli parser for one top-level command, wired to this console and its error handling. */
    public CommandLine commandLine(Class<?> command) {
        CommandLine cl = new CommandLine(command, factory);
        cl.setOut(session.console().writer());
        cl.setErr(session.console().writer());
        cl.setCaseInsensitiveEnumValuesAllowed(true);
        cl.setUsageHelpWidth(90);
        cl.setParameterExceptionHandler(this::onParseError);
        cl.setExecutionExceptionHandler((ex, cmd, parseResult) -> onExecutionError(ex));
        return cl;
    }

    // ------------------------------------------------------------------ errors

    /** The line didn't match its command's shape (picocli's parse errors). */
    private int onParseError(ParameterException ex, String[] args) {
        if (ex instanceof UsageException usage) {
            return onUsageError(usage);
        }
        CommandLine cmd = ex.getCommandLine();
        print(ex.getMessage());
        if (ex instanceof UnmatchedArgumentException && !cmd.getSubcommands().isEmpty() && args.length > 0
                && !args[0].startsWith("-")) {
            print("Choose one of: " + String.join(", ", cmd.getSubcommands().keySet()) + ".");
        } else if (ex instanceof UnmatchedArgumentException) {
            print("Values with spaces must be in quotes, e.g. \"Monstera deliciosa\".");
        }
        print("Usage: " + synopsis(cmd));
        print("Type /help " + cmd.getCommandSpec().qualifiedName().substring(1) + " for details.");
        if (offerSuggestions) {
            suggestCommand(currentLine);
        }
        return 2;
    }

    /** A usage rule the command checks itself (a noun without a verb, an empty message): no agent suggestion. */
    private int onUsageError(ParameterException ex) {
        print(ex.getMessage());
        if (ex.getCommandLine().getSubcommands().isEmpty()) {
            print("Usage: " + synopsis(ex.getCommandLine()));
        } else {
            ex.getCommandLine().usage(session.console().writer());
            session.console().writer().flush();
        }
        return 2;
    }

    private int onExecutionError(Exception ex) {
        if (ex instanceof ParameterException pe) {
            return onUsageError(pe);
        }
        if (ex instanceof ApiException api && api.retryable() && !api.getMessage().contains("try again")) {
            print(api.getMessage() + " Please try again in a moment.");
            return 1;
        }
        if (ex instanceof CliException cli) {
            print(cli.getMessage());
            return 1;
        }
        print("Something went wrong: " + ex);
        return 1;
    }

    private static String synopsis(CommandLine cmd) {
        return cmd.getHelp().synopsis(0).replace(" [-h]", "").strip();
    }

    /**
     * Asks the agent what a malformed line meant (the server's interpret
     * endpoint runs only the planner: nothing is stored or changed) and offers
     * the matching command, which runs only after the user confirms it.
     */
    private void suggestCommand(String line) {
        String command;
        try {
            session.console().status("Asking the agent what you meant");
            JsonNode suggestion = session.api().post("/api/chat/interpret", Json.body("text", line));
            command = echoesInput(suggestion, line) ? null : CommandSuggestions.toCommand(suggestion, session.find());
        } catch (CliException e) {
            return;
        }
        if (command == null || command.equalsIgnoreCase(line)) {
            print("The agent couldn't tell which command you meant.");
            return;
        }
        print("The agent thinks you meant:\n  " + command);
        if (session.console().confirm("Run it?")) {
            session.runApproved(command);
        }
    }

    /**
     * True when the agent only handed the typed line back as a value (what the
     * server's keyword fallback does when it has no AI key): a command built
     * from that would be nonsense, so none is suggested.
     */
    static boolean echoesInput(JsonNode suggestion, String line) {
        String typed = line.replaceFirst("^/", "").strip().toLowerCase(Locale.ROOT);
        for (String field : new String[] {"details", "plantName", "spaceName"}) {
            String value = Json.str(suggestion, field);
            if (value != null && value.replaceFirst("^/", "").strip().toLowerCase(Locale.ROOT).contains(typed)) {
                return true;
            }
        }
        return false;
    }

    private void print(String text) {
        session.console().print(text);
    }

    /** Creates command objects for picocli, giving each the session through its constructor. */
    private record SessionFactory(Session session) implements CommandLine.IFactory {

        @Override
        public <K> K create(Class<K> cls) throws Exception {
            try {
                Constructor<K> constructor = cls.getDeclaredConstructor(Session.class);
                constructor.setAccessible(true);
                return constructor.newInstance(session);
            } catch (NoSuchMethodException e) {
                return CommandLine.defaultFactory().create(cls);
            }
        }
    }
}
