package com.plantorganizer.cli;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.URI;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import tools.jackson.databind.JsonNode;

/**
 * Entry point of Agent.jar, the terminal version of the plant-care agent:
 *
 * <pre>java -jar Agent.jar [--user 1] [--server http://localhost:8080]</pre>
 *
 * The Plant Organizer server must already be running (./mvnw spring-boot:run).
 * At start-up the CLI checks that a Plant Organizer server answers at
 * --server and that the --user exists, then hands over to {@link CliConsole}.
 * Everything the agent does happens on the server; this program is a second
 * front end next to the web GUI and sees the same data.
 */
@Command(name = "java -jar Agent.jar", mixinStandardHelpOptions = true, separator = " ",
        version = "Plant Organizer agent CLI 1.0",
        description = "Talk to the Plant Organizer agent from a terminal. The server must already be running.")
public class AgentCli implements Callable<Integer> {

    @Option(names = "--user", paramLabel = "<id>", defaultValue = "1",
            description = "The user to act as (default: 1, the default user \"Me\").")
    String user;

    @Option(names = "--server", paramLabel = "<url>", defaultValue = "http://localhost:8080",
            description = "Where the Plant Organizer server runs (default: http://localhost:8080).")
    String server;

    private final LineInput in;
    private final PrintWriter out;

    public AgentCli(LineInput in, PrintWriter out) {
        this.in = in;
        this.out = out;
    }

    /**
     * In a terminal the prompt gets line editing, history and tab completion
     * ({@link TerminalInput}); with input piped in it reads plain lines.
     */
    public static void main(String[] args) {
        PrintWriter out = new PrintWriter(System.out, true);
        LineInput in = TerminalInput.open();
        if (in == null) {
            Charset charset = System.console() != null ? System.console().charset() : Charset.defaultCharset();
            in = LineInput.plain(new BufferedReader(new InputStreamReader(System.in, charset)), out);
        }
        int code;
        try {
            code = run(args, in, out);
        } finally {
            in.close();
        }
        System.exit(code);
    }

    /** Runs the CLI on plain lines from a reader (piped input, the tests). */
    public static int run(String[] args, BufferedReader in, PrintWriter out) {
        return run(args, LineInput.plain(in, out), out);
    }

    /** Parses the launch options and runs the CLI; returns the exit code (0 ok, 1 start-up failed, 2 bad options). */
    public static int run(String[] args, LineInput in, PrintWriter out) {
        CommandLine cl = new CommandLine(new AgentCli(in, out));
        cl.setOut(out);
        cl.setErr(out);
        return cl.execute(args);
    }

    @Override
    public Integer call() {
        CliConsole console = new CliConsole(in, out);
        if (!user.strip().matches("\\d+")) {
            console.print("--user takes a user ID, a number (e.g. --user 1).");
            return 2;
        }
        String url = server.strip().replaceAll("/+$", "");
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException e) {
            uri = null;
        }
        if (uri == null || !("http".equals(uri.getScheme()) || "https".equals(uri.getScheme())) || uri.getHost() == null) {
            console.print("--server must be a web address such as http://localhost:8080.");
            return 2;
        }
        AgentClient api = new AgentClient(url, Long.parseLong(user.strip()));

        JsonNode status;
        try {
            status = api.get("/api/status");
        } catch (CliException e) {
            console.print(new ServerUnavailableException(url).getMessage());
            return 1;
        }
        if (!"ok".equals(Json.str(status, "status"))) {
            console.print("Something answers at " + url + ", but it isn't a Plant Organizer server. "
                    + "Check the address given with --server.");
            return 1;
        }
        JsonNode me;
        try {
            me = api.get("/api/users/me");
        } catch (ApiException e) {
            console.print(e.status() == 404
                    ? "No such user found: " + user.strip() + ". Leave out --user to use the default user (1)."
                    : "Couldn't load user " + user.strip() + ": " + e.getMessage());
            return 1;
        }

        Session session = new Session(api, console, Json.str(me, "displayName"));
        CommandDispatcher dispatcher = new CommandDispatcher(session);
        if (in instanceof TerminalInput terminal) {
            terminal.completeWith(new CommandCompleter(session));
            terminal.quitOnInterrupt(out);
        }
        console.print("Plant Organizer agent - connected to " + url + " as " + session.userName()
                + " (user " + session.userId() + ").");
        for (String warning : missingKeys(status)) {
            console.print("Warning: " + warning);
        }
        console.print("Type /help to see the commands, or /quit to leave."
                + (in instanceof TerminalInput ? " Tab completes commands and names." : ""));
        console.run(session, dispatcher);
        return 0;
    }

    /** The server reports which API keys it has; without them some agent features can't work. */
    private static List<String> missingKeys(JsonNode status) {
        JsonNode keys = status.path("keysConfigured");
        List<String> warnings = new ArrayList<>();
        if (!keys.path("cohere").asBoolean(true)) {
            warnings.add("the server has no Cohere API key, so chat, text identification and AI answers are limited.");
        }
        if (!keys.path("plantnet").asBoolean(true)) {
            warnings.add("the server has no Pl@ntNet API key, so /identify photo won't work.");
        }
        if (!keys.path("perenual").asBoolean(true)) {
            warnings.add("the server has no Perenual API key, so care information comes from the AI only.");
        }
        return warnings;
    }
}
