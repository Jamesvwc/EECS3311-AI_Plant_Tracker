package com.plantorganizer.cli.commands;

import static com.plantorganizer.cli.CommandLineSplitter.quote;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
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

/** F08 (and F02, F04): the plants the user owns. A plant is named by its P-ID (P3), nickname or species name. */
@Command(name = "/plants", separator = " ", description = "The plants you own: list, add, remove, water, notes, care notes.",
        subcommands = {PlantsCommand.ListPlants.class, PlantsCommand.Show.class, PlantsCommand.Add.class,
                PlantsCommand.Remove.class, PlantsCommand.Water.class, PlantsCommand.Note.class, PlantsCommand.Notes.class,
                PlantsCommand.Rename.class, PlantsCommand.Care.class, PlantsCommand.Schedule.class,
                PlantsCommand.Research.class})
public class PlantsCommand extends GroupCommand {

    public static final String PLANT = "<plant>";
    static final String PLANT_HELP = "The plant's ID (e.g. P3), nickname or species name; names with spaces in quotes.";

    @Command(name = "list", separator = " ", description = "List your plants, grouped by species, with how many you have and their IDs.")
    static class ListPlants extends CliCommand {

        ListPlants(Session session) {
            super(session);
        }

        @Override
        protected void execute() {
            List<JsonNode> plants = find().ownedPlants();
            if (plants.isEmpty()) {
                print("No plants are owned. Add one with /plants add \"<scientific name>\", or identify one with /identify.");
                return;
            }
            Map<String, List<JsonNode>> bySpecies = new LinkedHashMap<>();
            plants.stream()
                    .sorted(java.util.Comparator.comparing((JsonNode p) -> Resolver.speciesName(p).toLowerCase(Locale.ROOT))
                            .thenComparingLong(Json::id))
                    .forEach(p -> bySpecies.computeIfAbsent(Json.str(p, "scientificName").toLowerCase(Locale.ROOT),
                            k -> new ArrayList<>()).add(p));
            List<String[]> rows = new ArrayList<>();
            for (List<JsonNode> group : bySpecies.values()) {
                JsonNode first = group.get(0);
                String common = Json.str(first, "commonName");
                String scientific = Json.str(first, "scientificName");
                String name = common != null && !common.equalsIgnoreCase(scientific) ? common + " (" + scientific + ")" : scientific;
                List<String> ids = group.stream().map(p -> Resolver.plantId(p)
                        + (Json.str(p, "nickname") != null ? " \"" + Json.str(p, "nickname") + "\"" : "")).toList();
                rows.add(new String[] {name, "x" + group.size(), String.join(", ", ids)});
            }
            print("Your plants (" + Text.count(plants.size(), "plant") + "):");
            print(Text.table(rows, "  "));
            print("Use a plant's ID (e.g. " + Resolver.plantId(plants.get(0)) + ") or name in other commands. "
                    + "Details: /plants show <plant>");
        }
    }

    @Command(name = "show", separator = " ", description = "Show one plant: care notes, watering, reminders, location, health.")
    static class Show extends CliCommand {

        @Parameters(index = "0", paramLabel = PLANT, description = PLANT_HELP)
        String plant;

        Show(Session session) {
            super(session);
        }

        @Override
        protected void execute() {
            JsonNode p = find().ownedPlant(plant);
            String id = Resolver.plantId(p);
            List<String[]> rows = new ArrayList<>();
            rows.add(new String[] {"Species:", Json.str(p, "scientificName")});
            rows.add(new String[] {"Location:", Json.str(p, "location") != null ? Json.str(p, "location")
                    : "Not placed (use /spaces place)"});
            Integer interval = p.path("wateringIntervalDays").isNumber() ? p.path("wateringIntervalDays").asInt() : null;
            rows.add(new String[] {"Watering:", (interval != null ? "every " + interval + " days" : "no schedule yet")
                    + " (" + Text.words(Json.str(p, "wateringStatus")) + ")"});
            rows.add(new String[] {"Next watering:", Text.when(Json.str(p, "nextWatering"))});
            rows.add(new String[] {"Last watered:", Text.when(Json.str(p, "lastWateredAt"))});
            rows.add(new String[] {"Reminders:", Json.bool(p, "notificationsEnabled") ? "on" : "off"});
            rows.add(new String[] {"Latest health:", Json.str(p, "latestHealth") != null
                    ? Text.words(Json.str(p, "latestHealth")) : "not checked yet"});
            rows.add(new String[] {"Added:", Text.when(Json.str(p, "addedAt"))});
            print(Resolver.plantLabel(p));
            print(Text.table(rows, "  "));
            print("Care notes:");
            print(Views.care(p.path("care")));
            String source = Views.source(p.path("care"));
            if (source != null) {
                print(source);
            }
            int notes = Json.list(api().get("/api/plants/" + Json.id(p) + "/notes")).size();
            print(Text.count(notes, "note") + (notes > 0 ? " (see /plants notes " + id + ")" : ""));
        }
    }

    @Command(name = "add", separator = " ",
            description = "Add a plant you own by its scientific name. Its care notes are looked up and a watering "
                    + "schedule is made. Names no plant database or the AI recognise are rejected.")
    static class Add extends CliCommand {

        @Parameters(index = "0", paramLabel = "<scientific name>", description = "e.g. \"Monstera deliciosa\" (in quotes).")
        String scientificName;

        @Option(names = "--common-name", paramLabel = "<name>", description = "Its everyday name, e.g. \"Swiss cheese plant\".")
        String commonName;

        @Option(names = "--nickname", paramLabel = "<nickname>", description = "Your name for this plant, e.g. \"Monty\".")
        String nickname;

        Add(Session session) {
            super(session);
        }

        @Override
        protected void execute() {
            if (!confirm("Add " + quote(scientificName) + (nickname != null ? " (\"" + nickname + "\")" : "")
                    + " to your plants?")) {
                return;
            }
            status("Checking the name, looking up care information and making a watering schedule");
            JsonNode result = api().post("/api/plants", Json.body("scientificName", scientificName,
                    "commonName", commonName, "nickname", nickname, "spaceId", null));
            print(Views.addedPlant(result));
        }
    }

    @Command(name = "remove", separator = " ", description = "Remove a plant you own (with its notes, health checks and schedule).")
    static class Remove extends CliCommand {

        @Parameters(index = "0", paramLabel = PLANT, description = PLANT_HELP)
        String plant;

        Remove(Session session) {
            super(session);
        }

        @Override
        protected void execute() {
            JsonNode p = find().ownedPlant(plant);
            if (!confirm("Remove " + Resolver.plantLabel(p) + "? Its notes, health checks and watering schedule "
                    + "are deleted too.")) {
                return;
            }
            api().delete("/api/plants/" + Json.id(p));
            print("Removed " + Resolver.plantLabel(p) + ".");
        }
    }

    @Command(name = "water", separator = " ", description = "Mark a plant as watered (its next watering is rescheduled).")
    static class Water extends CliCommand {

        @Parameters(index = "0", paramLabel = PLANT, description = PLANT_HELP)
        String plant;

        Water(Session session) {
            super(session);
        }

        @Override
        protected void execute() {
            JsonNode p = find().ownedPlant(plant);
            JsonNode result = api().post("/api/plants/" + Json.id(p) + "/water", null);
            print("Plant was watered. " + Text.plain(Json.str(result, "message")));
        }
    }

    @Command(name = "note", separator = " ", description = "Add a note to a plant, e.g. /plants note P3 \"repotted today\".")
    static class Note extends CliCommand {

        @Parameters(index = "0", paramLabel = PLANT, description = PLANT_HELP)
        String plant;

        @Parameters(index = "1", paramLabel = "<note>", description = "The note, in quotes.")
        String note;

        Note(Session session) {
            super(session);
        }

        @Override
        protected void execute() {
            JsonNode p = find().ownedPlant(plant);
            if (note.isBlank()) {
                throw new CliException("The note is empty. Write it in quotes, e.g. /plants note "
                        + Resolver.plantId(p) + " \"repotted today\".");
            }
            if (!confirm("Add the note " + quote(note) + " to " + Resolver.plantLabel(p) + "?")) {
                return;
            }
            api().post("/api/plants/" + Json.id(p) + "/notes", Json.body("text", note));
            print("Note added to " + Resolver.plantName(p) + ".");
        }
    }

    @Command(name = "notes", separator = " ", description = "List a plant's notes (yours, the agent's and saved health checks).")
    static class Notes extends CliCommand {

        @Parameters(index = "0", paramLabel = PLANT, description = PLANT_HELP)
        String plant;

        Notes(Session session) {
            super(session);
        }

        @Override
        protected void execute() {
            JsonNode p = find().ownedPlant(plant);
            List<JsonNode> notes = Json.list(api().get("/api/plants/" + Json.id(p) + "/notes"));
            if (notes.isEmpty()) {
                print(Resolver.plantName(p) + " has no notes yet. Add one with /plants note " + Resolver.plantId(p)
                        + " \"<note>\".");
                return;
            }
            print("Notes for " + Resolver.plantLabel(p) + ":");
            for (JsonNode n : notes) {
                String kind = switch (String.valueOf(Json.str(n, "kind"))) {
                    case "AGENT" -> "agent";
                    case "HEALTH" -> "health check";
                    default -> "you";
                };
                print("  " + Text.when(Json.str(n, "createdAt")) + " (" + kind + "):");
                print(Text.indent(Text.plain(Json.str(n, "text")), "    "));
            }
        }
    }

    @Command(name = "rename", separator = " ", description = "Give a plant a nickname (\"\" removes it).")
    static class Rename extends CliCommand {

        @Parameters(index = "0", paramLabel = PLANT, description = PLANT_HELP)
        String plant;

        @Parameters(index = "1", paramLabel = "<nickname>", description = "The new nickname, in quotes.")
        String nickname;

        Rename(Session session) {
            super(session);
        }

        @Override
        protected void execute() {
            JsonNode p = find().ownedPlant(plant);
            String name = nickname.isBlank() ? null : nickname.strip();
            JsonNode updated = api().put("/api/plants/" + Json.id(p), Json.body("nickname", name, "care", null));
            print(name == null ? Resolver.plantId(p) + " no longer has a nickname."
                    : Resolver.plantId(p) + " is now called \"" + Json.str(updated, "nickname") + "\".");
        }
    }

    @Command(name = "care", separator = " ",
            description = "Change a plant's care notes. Give only the fields to change; \"\" empties a field. "
                    + "Afterwards /plants schedule rebuilds its watering schedule from them.")
    static class Care extends CliCommand {

        @Parameters(index = "0", paramLabel = PLANT, description = PLANT_HELP)
        String plant;

        @Option(names = "--watering", paramLabel = "<amount>", completionCandidates = Choices.Watering.class,
                description = "Frequent, Average, Minimum or None.")
        String watering;

        @Option(names = "--frequency", paramLabel = "<how often>", description = "e.g. \"every 7-10 days\".")
        String frequency;

        @Option(names = "--sunlight", paramLabel = "<light>", description = "Comma-separated, e.g. \"bright indirect, part shade\".")
        String sunlight;

        @Option(names = "--care-level", paramLabel = "<level>", completionCandidates = Choices.CareLevel.class,
                description = "Easy, Medium or Hard.")
        String careLevel;

        @Option(names = "--growth-rate", paramLabel = "<rate>", completionCandidates = Choices.GrowthRate.class,
                description = "Low, Medium or High.")
        String growthRate;

        @Option(names = "--hardiness", paramLabel = "<zones>", description = "Hardiness zones, e.g. \"9-11\".")
        String hardiness;

        @Option(names = "--family", paramLabel = "<family>", description = "Botanical family.")
        String family;

        @Option(names = "--description", paramLabel = "<text>", description = "A description of the plant or its care.")
        String description;

        @Option(names = "--indoor", paramLabel = "<yes|no>", completionCandidates = Choices.YesNo.class,
                description = "Whether it's an indoor plant.")
        String indoor;

        @Option(names = "--toxic-to-pets", paramLabel = "<yes|no>", completionCandidates = Choices.YesNo.class,
                description = "Whether it's toxic to pets.")
        String toxicToPets;

        @Option(names = "--toxic-to-humans", paramLabel = "<yes|no>", completionCandidates = Choices.YesNo.class,
                description = "Whether it's toxic to humans.")
        String toxicToHumans;

        Care(Session session) {
            super(session);
        }

        @Override
        protected void execute() {
            if (java.util.stream.Stream.of(watering, frequency, sunlight, careLevel, growthRate, hardiness, family,
                    description, indoor, toxicToPets, toxicToHumans).allMatch(v -> v == null)) {
                throw new CliException("Give at least one field to change, e.g. /plants care " + plant
                        + " --watering Average. See /help plants care.");
            }
            JsonNode p = find().ownedPlant(plant);
            JsonNode current = p.path("care");
            Map<String, Object> care = new LinkedHashMap<>();
            care.put("scientificName", Json.str(p, "scientificName"));
            care.put("commonName", Json.str(p, "commonName"));
            care.put("family", pick(family, Json.str(current, "family")));
            care.put("description", pick(description, Json.str(current, "description")));
            care.put("watering", pick(watering, Json.str(current, "watering")));
            care.put("wateringFrequency", pick(frequency, Json.str(current, "wateringFrequency")));
            care.put("sunlight", sunlight != null
                    ? java.util.Arrays.stream(sunlight.split(",")).map(String::strip).filter(s -> !s.isEmpty()).toList()
                    : Json.strings(current.path("sunlight")));
            care.put("careLevel", pick(careLevel, Json.str(current, "careLevel")));
            care.put("growthRate", pick(growthRate, Json.str(current, "growthRate")));
            care.put("indoor", flag("--indoor", indoor, current.path("indoor")));
            care.put("poisonousToPets", flag("--toxic-to-pets", toxicToPets, current.path("poisonousToPets")));
            care.put("poisonousToHumans", flag("--toxic-to-humans", toxicToHumans, current.path("poisonousToHumans")));
            care.put("hardinessZones", pick(hardiness, Json.str(current, "hardinessZones")));
            JsonNode updated = api().put("/api/plants/" + Json.id(p),
                    Json.body("nickname", Json.str(p, "nickname"), "care", care));
            print("Updated the care notes of " + Resolver.plantLabel(p) + ":");
            print(Views.care(updated.path("care")));
            if (watering != null || frequency != null) {
                print("Rebuild its watering schedule from the new notes with /plants schedule " + Resolver.plantId(p) + ".");
            }
        }

        private static String pick(String given, String current) {
            return given != null ? given.strip() : current;
        }

        private static Boolean flag(String option, String given, JsonNode current) {
            if (given == null) {
                return current.isMissingNode() || current.isNull() ? null : current.asBoolean();
            }
            return switch (given.strip().toLowerCase(Locale.ROOT)) {
                case "yes", "y", "true" -> Boolean.TRUE;
                case "no", "n", "false" -> Boolean.FALSE;
                case "" -> null;
                default -> throw new CliException(option + " takes yes or no (or \"\" for unknown).");
            };
        }
    }

    @Command(name = "schedule", separator = " ", description = "Rebuild a plant's watering schedule from its care notes.")
    static class Schedule extends CliCommand {

        @Parameters(index = "0", paramLabel = PLANT, description = PLANT_HELP)
        String plant;

        Schedule(Session session) {
            super(session);
        }

        @Override
        protected void execute() {
            JsonNode p = find().ownedPlant(plant);
            status("Rebuilding the watering schedule of " + Resolver.plantName(p));
            print(Views.schedule(api().post("/api/plants/" + Json.id(p) + "/schedule", null), Resolver.plantId(p)));
        }
    }

    @Command(name = "research", separator = " ",
            description = "Search the web for better watering information, then rebuild the plant's schedule.")
    static class Research extends CliCommand {

        @Parameters(index = "0", paramLabel = PLANT, description = PLANT_HELP)
        String plant;

        Research(Session session) {
            super(session);
        }

        @Override
        protected void execute() {
            JsonNode p = find().ownedPlant(plant);
            status("Searching the web for how often to water " + Resolver.plantName(p));
            print(Views.schedule(api().post("/api/plants/" + Json.id(p) + "/schedule/research", null), Resolver.plantId(p)));
        }
    }
}
