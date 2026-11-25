package com.plantorganizer.cli.commands;

import java.util.List;

import com.plantorganizer.cli.CommandSuggestions;
import com.plantorganizer.cli.Json;
import com.plantorganizer.cli.Session;
import com.plantorganizer.cli.Text;

import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;
import tools.jackson.databind.JsonNode;

/**
 * Free-form, read-only chat with the agent. Questions are answered directly
 * (the agent may look up care information or what needs water). When a
 * message asks for a change, the server runs nothing and returns a structured
 * suggestion; this command turns it into the matching /command, shows it, and
 * runs it only if the user confirms.
 */
@Command(name = "/chat", separator = " ",
        description = "Ask the agent anything about plants. Chat never changes your data: if you ask for a change, "
                + "the agent suggests the command for it and runs it only if you confirm.",
        subcommands = {ChatCommand.History.class, ChatCommand.Clear.class})
public class ChatCommand extends CliCommand {

    @Parameters(index = "0", arity = "0..1", paramLabel = "<message>",
            description = "Your message, in quotes, e.g. \"how often should I water my monstera?\".")
    String message;

    ChatCommand(Session session) {
        super(session);
    }

    @Override
    protected void execute() {
        if (message == null || message.isBlank()) {
            throw new com.plantorganizer.cli.UsageException(spec.commandLine(),
                    "Write your message in quotes, e.g. /chat \"how often should I water my monstera?\"");
        }
        status("The agent is thinking");
        JsonNode reply = api().post("/api/chat/ask", Json.body("message", message));
        print("Agent: " + Text.indent(Text.plain(Json.str(reply, "text")), "       ").strip());
        List<String> tools = Json.strings(reply.path("toolsUsed"));
        print("(understood as " + Text.words(Json.str(reply, "intent"))
                + (tools.isEmpty() ? "" : "; tools used: " + String.join(", ", tools).toLowerCase()) + ")");
        JsonNode suggestion = reply.path("suggestion");
        if (suggestion.isMissingNode() || suggestion.isNull()) {
            return;
        }
        String command = CommandSuggestions.toCommand(suggestion, find());
        if (command == null) {
            String template = CommandSuggestions.template(suggestion);
            if (template != null) {
                print("Use: " + template);
            }
            return;
        }
        print("Suggested command:\n  " + command);
        if (session.confirm("Run it now?")) {
            session.runApproved(command);
        } else {
            print("Not run.");
        }
    }

    @Command(name = "history", separator = " ", description = "Show your conversation with the agent (shared with the GUI chat box).")
    static class History extends CliCommand {

        History(Session session) {
            super(session);
        }

        @Override
        protected void execute() {
            List<JsonNode> messages = Json.list(api().get("/api/chat/history"));
            if (messages.isEmpty()) {
                print("No conversation yet. Start one with /chat \"<message>\".");
                return;
            }
            for (JsonNode m : messages) {
                String who = "USER".equals(Json.str(m, "role")) ? "You:   " : "Agent: ";
                print(who + Text.indent(Text.plain(Json.str(m, "text")), "       ").strip());
            }
        }
    }

    @Command(name = "clear", separator = " ", description = "Delete your conversation with the agent.")
    static class Clear extends CliCommand {

        Clear(Session session) {
            super(session);
        }

        @Override
        protected void execute() {
            if (!confirm("Delete your whole conversation with the agent?")) {
                return;
            }
            api().delete("/api/chat/history");
            print("Conversation deleted.");
        }
    }
}
