package com.plantorganizer.cli.commands;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.plantorganizer.cli.Json;
import com.plantorganizer.cli.Resolver;
import com.plantorganizer.cli.Session;
import com.plantorganizer.cli.Text;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import com.plantorganizer.cli.UsageException;

import picocli.CommandLine.Parameters;
import tools.jackson.databind.JsonNode;

/**
 * F06/F07: plants the agent recommends. "keep" leaves a plant on this list
 * (and teaches the agent you like it); it does not add it to your wishlist,
 * which is {@code /wishlist add}.
 */
@Command(name = "/recommend", separator = " ",
        description = "Plants the agent recommends: your list, automatic suggestions, or suggestions for a request.",
        subcommands = {RecommendCommand.ListRecs.class, RecommendCommand.Auto.class, RecommendCommand.Ask.class,
                RecommendCommand.Keep.class, RecommendCommand.Reject.class})
public class RecommendCommand extends GroupCommand {

    public static final String REC = "<recommendation>";
    static final String REC_HELP = "The recommendation's ID (e.g. R7) or name; names with spaces in quotes.";

    enum SortBy { CARE, NAME, SPACE }

    @Command(name = "list", separator = " ", description = "List the plants recommended to you, with their IDs.")
    static class ListRecs extends CliCommand {

        @Option(names = "--sort", paramLabel = "<care|name|space>", defaultValue = "care",
                description = "Order by ease of care (default), name, or recommended space.")
        SortBy sort;

        ListRecs(Session session) {
            super(session);
        }

        @Override
        protected void execute() {
            status("Loading your recommendations");
            JsonNode list = api().get("/api/recommendations?sort=" + sort.name());
            List<JsonNode> items = Json.list(list.path("items"));
            if (items.isEmpty()) {
                String message = Json.str(list, "message");
                print("No recommendations yet." + (message != null ? " " + message : "")
                        + " Try /recommend auto or /recommend ask \"<what you're looking for>\".");
                return;
            }
            print("Recommended for you (" + Text.count(items.size(), "plant") + "):");
            print(rows(items));
        }
    }

    @Command(name = "auto", separator = " ",
            description = "The agent recommends plants from what it knows about you (your plants and the ones you kept).")
    static class Auto extends CliCommand {

        Auto(Session session) {
            super(session);
        }

        @Override
        protected void execute() {
            status("The agent is choosing plants from what it knows about you");
            List<JsonNode> items = Json.list(api().post("/api/recommendations/refresh", null));
            if (items.isEmpty()) {
                print("The agent has no recommendations for you right now.");
                return;
            }
            print("Recommended for you (" + Text.count(items.size(), "plant") + "):");
            print(rows(items));
        }
    }

    @Command(name = "ask", separator = " ",
            description = "Ask for plants that fit a request, e.g. /recommend ask \"low maintenance and safe for cats\".")
    static class Ask extends CliCommand {

        @Parameters(index = "0", arity = "0..1", paramLabel = "<what you'd like>",
                description = "The kind of plants you'd like, in quotes.")
        String request;

        Ask(Session session) {
            super(session);
        }

        @Override
        protected void execute() {
            if (request == null || request.isBlank()) {
                throw new UsageException(spec.commandLine(), "Incorrect usage. Try /recommend ask \"...\", "
                        + "where \"...\" is the kind of plants you would like.");
            }
            status("The agent is looking for plants that fit");
            List<JsonNode> items = Json.list(api().post("/api/recommendations/request", Json.body("constraints", request)));
            session.setSuggestedRecommendations(items);
            if (items.isEmpty()) {
                print("The agent couldn't find plants that fit that. Try describing it differently.");
                return;
            }
            print("Suggestions for \"" + request + "\":");
            print(rows(items));
            print("Keep the ones you like on your recommendations with /recommend keep <R-ID>.");
            print("To put one on your wishlist instead: /wishlist add \"<scientific name>\".");
        }
    }

    @Command(name = "keep", separator = " ",
            description = "Keep a recommendation on your list; the agent remembers you like it. (It isn't added to "
                    + "your wishlist: use /wishlist add for that.)")
    static class Keep extends CliCommand {

        @Parameters(index = "0", paramLabel = REC, description = REC_HELP)
        String recommendation;

        Keep(Session session) {
            super(session);
        }

        @Override
        protected void execute() {
            JsonNode rec = find().recommendation(recommendation, true);
            JsonNode kept = api().post("/api/recommendations/" + Json.id(rec) + "/accept", null);
            print("Kept " + Resolver.displayName(kept) + " on your recommendations. The agent will remember you like it.");
            print("To add it to your wishlist too: /wishlist add \"" + Json.str(kept, "scientificName") + "\"");
        }
    }

    @Command(name = "reject", separator = " ",
            description = "Remove a recommendation; the agent remembers you don't want it.")
    static class Reject extends CliCommand {

        @Parameters(index = "0", paramLabel = REC, description = REC_HELP)
        String recommendation;

        Reject(Session session) {
            super(session);
        }

        @Override
        protected void execute() {
            JsonNode rec = find().recommendation(recommendation, false);
            if (!confirm("Remove " + Resolver.recommendationLabel(rec) + " from your recommendations? "
                    + "The agent will remember you don't want it.")) {
                return;
            }
            api().delete("/api/recommendations/" + Json.id(rec));
            print("Removed " + Resolver.displayName(rec) + ". The agent won't suggest it again.");
        }
    }

    static String rows(List<JsonNode> items) {
        List<String> lines = new ArrayList<>();
        for (JsonNode r : items) {
            List<String> facts = new ArrayList<>();
            if (Json.str(r, "careLevel") != null) {
                facts.add("care: " + Json.str(r, "careLevel").toLowerCase(Locale.ROOT));
            }
            if (r.path("petSafe").isBoolean()) {
                facts.add(r.path("petSafe").asBoolean() ? "pet safe" : "not pet safe");
            }
            if (Json.str(r, "recommendedSpace") != null) {
                facts.add("space: " + Json.str(r, "recommendedSpace"));
            }
            lines.add("  " + Resolver.recommendationLabel(r) + (facts.isEmpty() ? "" : "  [" + String.join(", ", facts) + "]"));
            if (Json.str(r, "reason") != null) {
                lines.add("      " + Text.plain(Json.str(r, "reason")));
            }
        }
        return String.join("\n", lines);
    }
}
