package com.plantorganizer.cli.commands;

import static com.plantorganizer.cli.CommandLineSplitter.quote;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.plantorganizer.cli.ApiException;
import com.plantorganizer.cli.CliException;
import com.plantorganizer.cli.Json;
import com.plantorganizer.cli.Resolver;
import com.plantorganizer.cli.Session;
import com.plantorganizer.cli.Text;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import tools.jackson.databind.JsonNode;

/** F09: plants the user would like to get. A wishlist item is named by its W-ID (W4) or its name. */
@Command(name = "/wishlist", separator = " ", description = "Plants you'd like to get: list, add, remove, bought, fit check.",
        subcommands = {WishlistCommand.ListItems.class, WishlistCommand.Add.class, WishlistCommand.Remove.class,
                WishlistCommand.Bought.class, WishlistCommand.Fit.class, WishlistCommand.Research.class})
public class WishlistCommand extends GroupCommand {

    public static final String ITEM = "<item>";
    static final String ITEM_HELP = "The wishlist item's ID (e.g. W4) or name; names with spaces in quotes.";

    @Command(name = "list", separator = " ", description = "List your wishlist with IDs, the space you picked and the fit verdict.")
    static class ListItems extends CliCommand {

        ListItems(Session session) {
            super(session);
        }

        @Override
        protected void execute() {
            List<JsonNode> items = find().wishlist();
            if (items.isEmpty()) {
                print("No plants are wishlisted. Add one with /wishlist add \"<scientific name>\".");
                return;
            }
            Map<Long, String> paths = new HashMap<>();
            if (items.stream().anyMatch(i -> i.path("intendedSpaceId").isNumber())) {
                find().spaces().forEach(room -> {
                    paths.put(Json.id(room), Json.str(room, "path"));
                    Resolver.descendants(room).forEach(s -> paths.put(Json.id(s), Json.str(s, "path")));
                });
            }
            List<String[]> rows = new ArrayList<>();
            for (JsonNode item : items) {
                String space = item.path("intendedSpaceId").isNumber()
                        ? "for " + paths.getOrDefault(item.path("intendedSpaceId").asLong(), "a space") : "no space picked";
                String fit = Json.str(item, "fitVerdict") != null ? "fit: " + Text.words(Json.str(item, "fitVerdict")) : "";
                rows.add(new String[] {"W" + Json.id(item), Resolver.displayName(item), space, fit});
            }
            print("Your wishlist (" + Text.count(items.size(), "plant") + "):");
            print(Text.table(rows, "  "));
        }
    }

    @Command(name = "add", separator = " ",
            description = "Add a plant to your wishlist by its scientific name. Names no plant database or the AI "
                    + "recognise are rejected.")
    static class Add extends CliCommand {

        @Parameters(index = "0", paramLabel = "<scientific name>", description = "e.g. \"Ficus lyrata\" (in quotes).")
        String scientificName;

        @Option(names = "--common-name", paramLabel = "<name>", description = "Its everyday name, e.g. \"Fiddle-leaf fig\".")
        String commonName;

        @Option(names = "--note", paramLabel = "<note>", description = "A note for yourself, in quotes.")
        String note;

        Add(Session session) {
            super(session);
        }

        @Override
        protected void execute() {
            if (!confirm("Add " + quote(scientificName) + " to your wishlist?")) {
                return;
            }
            status("Checking the name");
            JsonNode item = api().post("/api/desired", Json.body("scientificName", scientificName,
                    "commonName", commonName, "spaceId", null, "note", note, "imageUrl", null));
            print("Added " + Resolver.displayName(item) + " to your wishlist as W" + Json.id(item) + ".");
            print("Check whether a space suits it with /wishlist fit W" + Json.id(item) + " --room \"<room>\".");
        }
    }

    @Command(name = "remove", separator = " ", description = "Remove a plant from your wishlist.")
    static class Remove extends CliCommand {

        @Parameters(index = "0", paramLabel = ITEM, description = ITEM_HELP)
        String item;

        Remove(Session session) {
            super(session);
        }

        @Override
        protected void execute() {
            JsonNode w = find().wishlistItem(item);
            if (!confirm("Remove " + Resolver.wishlistLabel(w) + " from your wishlist?")) {
                return;
            }
            api().delete("/api/desired/" + Json.id(w));
            print("Removed " + Resolver.displayName(w) + " from your wishlist.");
        }
    }

    @Command(name = "bought", separator = " ",
            description = "You bought a wishlist plant: it moves to your plants (with care notes and a watering schedule).")
    static class Bought extends CliCommand {

        @Parameters(index = "0", paramLabel = ITEM, description = ITEM_HELP)
        String item;

        Bought(Session session) {
            super(session);
        }

        @Override
        protected void execute() {
            JsonNode w = find().wishlistItem(item);
            if (!confirm("Move " + Resolver.wishlistLabel(w) + " from your wishlist to your plants?")) {
                return;
            }
            status("Adding it to your plants and making a watering schedule");
            print(Views.addedPlant(api().post("/api/desired/" + Json.id(w) + "/buy", null)));
        }
    }

    @Command(name = "fit", separator = " ",
            description = "Ask the agent whether a space suits a wishlist plant. With --room (and --spot) that space "
                    + "is chosen first; without, the space picked before is used.")
    static class Fit extends CliCommand {

        @Parameters(index = "0", paramLabel = ITEM, description = ITEM_HELP)
        String item;

        @Option(names = "--room", paramLabel = "<room>", description = "The room you'd put it in, in quotes.")
        String room;

        @Option(names = "--spot", paramLabel = "<spot>", description = "A spot inside that room, in quotes.")
        String spot;

        Fit(Session session) {
            super(session);
        }

        @Override
        protected void execute() {
            if (spot != null && room == null) {
                throw new CliException("--spot needs --room too, e.g. --room \"Living room\" --spot \"Window sill\".");
            }
            JsonNode w = find().wishlistItem(item);
            status("The agent is checking whether the space suits " + Resolver.displayName(w));
            if (room != null) {
                JsonNode space = find().space(room, spot);
                JsonNode updated = api().put("/api/desired/" + Json.id(w),
                        Json.body("spaceId", Json.id(space), "note", Json.str(w, "note")));
                print(verdict(Json.str(space, "path"), Json.str(updated, "fitVerdict"), Json.str(updated, "fitExplanation")));
                return;
            }
            try {
                JsonNode fit = api().post("/api/desired/" + Json.id(w) + "/fit", null);
                print(verdict(null, Json.str(fit, "verdict"), Json.str(fit, "explanation")));
                if (Json.bool(fit, "careDataMissing")) {
                    print("Search the web for its needs with /wishlist research W" + Json.id(w) + ".");
                }
            } catch (ApiException e) {
                if (e.status() == 400) {
                    throw new CliException(e.getMessage() + " Add --room \"<room>\" (and --spot \"<spot>\").");
                }
                throw e;
            }
        }

        private static String verdict(String space, String verdict, String explanation) {
            return "Fit" + (space != null ? " for " + space : "") + ": " + Text.words(verdict)
                    + (explanation != null ? "\n" + Text.plain(explanation) : "");
        }
    }

    @Command(name = "research", separator = " ", description = "Search the web for a wishlist plant's light, watering and care needs.")
    static class Research extends CliCommand {

        @Parameters(index = "0", paramLabel = ITEM, description = ITEM_HELP)
        String item;

        Research(Session session) {
            super(session);
        }

        @Override
        protected void execute() {
            JsonNode w = find().wishlistItem(item);
            status("Searching the web about " + Resolver.displayName(w));
            print(Views.research(api().post("/api/desired/" + Json.id(w) + "/research", null)));
        }
    }
}
