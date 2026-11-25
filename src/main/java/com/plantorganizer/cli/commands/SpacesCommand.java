package com.plantorganizer.cli.commands;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.plantorganizer.cli.CliException;
import com.plantorganizer.cli.Json;
import com.plantorganizer.cli.Resolver;
import com.plantorganizer.cli.Session;
import com.plantorganizer.cli.Text;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import tools.jackson.databind.JsonNode;

/** F10: rooms, the spots inside them, and which plants are where. Rooms and spots are named in quotes. */
@Command(name = "/spaces", separator = " ", description = "Rooms and spots in your home, and which plants are where.",
        subcommands = {SpacesCommand.ListSpaces.class, SpacesCommand.Show.class, SpacesCommand.AddRoom.class,
                SpacesCommand.AddSpot.class, SpacesCommand.Place.class, SpacesCommand.Unassign.class,
                SpacesCommand.Remove.class})
public class SpacesCommand extends GroupCommand {

    @Command(name = "list", separator = " ", description = "List your rooms and spots with their light and plants.")
    static class ListSpaces extends CliCommand {

        ListSpaces(Session session) {
            super(session);
        }

        @Override
        protected void execute() {
            List<JsonNode> rooms = find().spaces();
            if (rooms.isEmpty()) {
                print("You have no rooms yet. Add one with /spaces add-room \"<room>\" <light>.");
                return;
            }
            print("Your spaces:");
            for (JsonNode room : rooms) {
                printTree(room, "  ");
            }
        }

        private void printTree(JsonNode space, String indent) {
            print(line(space, indent));
            for (JsonNode child : Json.list(space.path("children"))) {
                printTree(child, indent + "    ");
            }
        }

        private static String line(JsonNode space, String indent) {
            List<String> plants = Json.list(space.path("plants")).stream()
                    .map(p -> Json.str(p, "name") + " (P" + Json.id(p) + ")").toList();
            String light = Json.str(space, "effectiveLight");
            return indent + ("SPOT".equals(Json.str(space, "kind")) ? "- " : "") + Json.str(space, "name")
                    + (light != null ? "  [" + light + (Json.str(space, "lightLevel") == null ? ", from the room" : "") + "]" : "")
                    + ("SPOT".equals(Json.str(space, "kind"))
                       ? (plants.isEmpty() ? "  no plants" : "  " + String.join(", ", plants))
                       : "  (" + Text.count(space.path("plantCount").asInt(), "plant") + ")");
        }
    }

    @Command(name = "show", separator = " ", description = "List every plant in a room, or in one spot with --spot.")
    static class Show extends CliCommand {

        @Parameters(index = "0", paramLabel = "<room>", description = "The room's name, in quotes.")
        String room;

        @Option(names = "--spot", paramLabel = "<spot>", description = "Only this spot in the room.")
        String spot;

        Show(Session session) {
            super(session);
        }

        @Override
        protected void execute() {
            JsonNode space = find().space(room, spot);
            List<JsonNode> plants = Json.list(api().get("/api/spaces/" + Json.id(space) + "/plants"));
            if (plants.isEmpty()) {
                print("There are no plants in " + Json.str(space, "path") + ".");
                return;
            }
            print("Plants in " + Json.str(space, "path") + ":");
            for (JsonNode p : plants) {
                print("  " + Resolver.plantLabel(p) + (Json.str(p, "location") != null ? "  at " + Json.str(p, "location") : ""));
            }
        }
    }

    @Command(name = "add-room", separator = " ", description = "Add a room and how much light it gets.")
    static class AddRoom extends CliCommand {

        @Parameters(index = "0", paramLabel = "<room>", description = "The room's name, in quotes, e.g. \"Living room\".")
        String room;

        @Parameters(index = "1", paramLabel = "<light>", completionCandidates = Choices.Light.class,
                description = "low, medium, bright-indirect or direct-sun.")
        String light;

        AddRoom(Session session) {
            super(session);
        }

        @Override
        protected void execute() {
            String level = lightLevel(this, light);
            if (!confirm("Add the room \"" + room + "\" (" + light + ")?")) {
                return;
            }
            JsonNode created = api().post("/api/spaces", Json.body("name", room, "light", level, "parentId", null, "spot", false));
            print("Added the room " + Json.str(created, "name") + ". Add spots to it with /spaces add-spot \""
                    + Json.str(created, "name") + "\" \"<spot>\".");
        }
    }

    @Command(name = "add-spot", separator = " ",
            description = "Add a spot (a shelf, a window sill...) to a room. Without --light it gets the room's light.")
    static class AddSpot extends CliCommand {

        @Parameters(index = "0", paramLabel = "<room>", description = "The room's name, in quotes.")
        String room;

        @Parameters(index = "1", paramLabel = "<spot>", description = "The spot's name, in quotes, e.g. \"Window sill\".")
        String spot;

        @Option(names = "--light", paramLabel = "<light>", completionCandidates = Choices.Light.class,
                description = "low, medium, bright-indirect or direct-sun.")
        String light;

        AddSpot(Session session) {
            super(session);
        }

        @Override
        protected void execute() {
            String level = light != null ? lightLevel(this, light) : null;
            JsonNode r = find().room(room);
            if (!confirm("Add the spot \"" + spot + "\" to " + Json.str(r, "name")
                    + (light != null ? " (" + light + ")" : "") + "?")) {
                return;
            }
            JsonNode created = api().post("/api/spaces", Json.body("name", spot, "light", level, "parentId", Json.id(r), "spot", true));
            print("Added " + Json.str(created, "path") + ". Put a plant there with /spaces place \"" + Json.str(r, "name")
                    + "\" \"" + Json.str(created, "name") + "\" <plant>.");
        }
    }

    @Command(name = "place", separator = " ", description = "Put one of your plants in a spot of a room (or move it there).")
    static class Place extends CliCommand {

        @Parameters(index = "0", paramLabel = "<room>", description = "The room's name, in quotes.")
        String room;

        @Parameters(index = "1", paramLabel = "<spot>", description = "The spot's name, in quotes.")
        String spot;

        @Parameters(index = "2", paramLabel = PlantsCommand.PLANT, description = PlantsCommand.PLANT_HELP)
        String plant;

        Place(Session session) {
            super(session);
        }

        @Override
        protected void execute() {
            JsonNode target = find().space(room, spot);
            JsonNode p = find().ownedPlant(plant);
            JsonNode result = api().post("/api/spaces/" + Json.id(target) + "/plants/" + Json.id(p), null);
            print(Resolver.plantName(p) + " is now in " + Json.str(result, "path") + ".");
        }
    }

    @Command(name = "unassign", separator = " ", description = "Take a plant out of its spot (it stays in your plants).")
    static class Unassign extends CliCommand {

        @Parameters(index = "0", paramLabel = PlantsCommand.PLANT, description = PlantsCommand.PLANT_HELP)
        String plant;

        Unassign(Session session) {
            super(session);
        }

        @Override
        protected void execute() {
            JsonNode p = find().ownedPlant(plant);
            api().delete("/api/spaces/plants/" + Json.id(p));
            print(Resolver.plantName(p) + " is no longer placed in a space.");
        }
    }

    @Command(name = "remove", separator = " ",
            description = "Remove a room (with its spots), or one spot with --spot. Plants in it are only unplaced.")
    static class Remove extends CliCommand {

        @Parameters(index = "0", paramLabel = "<room>", description = "The room's name, in quotes.")
        String room;

        @Option(names = "--spot", paramLabel = "<spot>", description = "Remove only this spot of the room.")
        String spot;

        Remove(Session session) {
            super(session);
        }

        @Override
        protected void execute() {
            JsonNode space = find().space(room, spot);
            if (!confirm("Remove " + Json.str(space, "path") + (spot == null ? " and all its spots" : "")
                    + "? Plants in it stay in your plants, just unplaced.")) {
                return;
            }
            api().delete("/api/spaces/" + Json.id(space));
            print("Removed " + Json.str(space, "path") + ".");
        }
    }

    /**
     * The LightLevel value for what the user typed ("bright indirect", "Direct-Sun", "low"...).
     *
     * @throws CliException listing the valid options when it matches none
     */
    static String lightLevel(CliCommand command, String typed) {
        String wanted = typed.strip().toUpperCase(Locale.ROOT).replaceAll("[\\s-]+", "_");
        List<JsonNode> levels = Json.list(command.api().get("/api/spaces/light-levels"));
        for (JsonNode level : levels) {
            String value = Json.str(level, "value");
            String label = Json.str(level, "label");
            if (value.equals(wanted) || (label != null && label.equalsIgnoreCase(typed.strip()))) {
                return value;
            }
        }
        List<String> options = new ArrayList<>();
        for (JsonNode level : levels) {
            options.add(Json.str(level, "value").toLowerCase(Locale.ROOT).replace('_', '-') + " (" + Json.str(level, "label") + ")");
        }
        throw new CliException("\"" + typed + "\" isn't a light level. Choose one of: " + String.join(", ", options) + ".");
    }
}
