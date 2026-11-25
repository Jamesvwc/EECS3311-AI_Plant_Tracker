package com.plantorganizer.cli.commands;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.plantorganizer.cli.CliException;
import com.plantorganizer.cli.Json;
import com.plantorganizer.cli.Resolver;
import com.plantorganizer.cli.Session;
import com.plantorganizer.cli.Text;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import tools.jackson.databind.JsonNode;

/** F12: links that show chosen plants' watering and sunlight needs to anyone, without an account. */
@Command(name = "/share", separator = " ", description = "Share your plants' watering schedules with a link anyone can open.",
        subcommands = {ShareCommand.ListLinks.class, ShareCommand.Create.class, ShareCommand.Revoke.class})
public class ShareCommand extends GroupCommand {

    @Command(name = "list", separator = " ", description = "List the watering-schedule links you've shared.")
    static class ListLinks extends CliCommand {

        ListLinks(Session session) {
            super(session);
        }

        @Override
        protected void execute() {
            List<JsonNode> links = Json.list(api().get("/api/share"));
            if (links.isEmpty()) {
                print("No watering schedules have been shared. Create a link with /share create <plant> [<plant> ...].");
                return;
            }
            print("Shared watering schedules (" + links.size() + "):");
            for (JsonNode l : links) {
                print("  " + url(this, l));
                print("    " + (Json.str(l, "label") != null ? "\"" + Json.str(l, "label") + "\": " : "")
                        + String.join(", ", Json.strings(l.path("plantNames"))) + "  (expires " + Text.when(Json.str(l, "expiresAt")) + ")");
            }
        }
    }

    @Command(name = "create", separator = " ",
            description = "Create a link showing these plants' watering and sunlight needs, e.g. for a plant sitter.")
    static class Create extends CliCommand {

        @Parameters(arity = "1..*", paramLabel = "<plant>", description = "One or more of your plants (IDs or names in quotes).")
        List<String> plants;

        @Option(names = "--label", paramLabel = "<reason>", description = "What the link is for, e.g. \"for Sam while I'm away\".")
        String label;

        Create(Session session) {
            super(session);
        }

        @Override
        protected void execute() {
            Map<Long, JsonNode> chosen = new LinkedHashMap<>();
            for (String plant : plants) {
                JsonNode p = find().ownedPlant(plant);
                chosen.put(Json.id(p), p);
            }
            List<String> names = new ArrayList<>(chosen.values().stream().map(Resolver::plantLabel).toList());
            if (!confirm("Create a share link for " + String.join(", ", names) + "?")) {
                return;
            }
            JsonNode link = api().post("/api/share", Json.body("plantIds", new ArrayList<>(chosen.keySet()), "label", label));
            print("Share link created. Anyone with it can see these plants' watering and sunlight needs:");
            print("  " + url(this, link));
            print("Expires " + Text.when(Json.str(link, "expiresAt")) + ". Stop sharing with /share revoke <link>.");
        }
    }

    @Command(name = "revoke", separator = " ", description = "Stop sharing a link (it stops working).")
    static class Revoke extends CliCommand {

        @Parameters(index = "0", paramLabel = "<link>", description = "The link, or just its token.")
        String link;

        Revoke(Session session) {
            super(session);
        }

        @Override
        protected void execute() {
            String token = link.strip().replaceFirst("^.*[?&]token=", "").replaceFirst("[&#].*$", "");
            JsonNode existing = Json.list(api().get("/api/share")).stream().filter(l -> token.equals(Json.str(l, "token")))
                    .findFirst().orElseThrow(() -> new CliException("None of your shared links matches \"" + link
                            + "\". See /share list."));
            if (!confirm("Stop sharing " + String.join(", ", Json.strings(existing.path("plantNames"))) + " (" + token + ")?")) {
                return;
            }
            api().delete("/api/share/" + token);
            print("That link no longer works.");
        }
    }

    static String url(CliCommand command, JsonNode link) {
        return command.api().baseUrl() + "/" + Json.str(link, "path");
    }
}
