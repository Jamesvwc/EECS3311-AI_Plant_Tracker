package com.plantorganizer.cli.commands;

import static com.plantorganizer.cli.CommandLineSplitter.quote;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.plantorganizer.cli.AgentClient;
import com.plantorganizer.cli.CliException;
import com.plantorganizer.cli.Json;
import com.plantorganizer.cli.Session;
import com.plantorganizer.cli.Session.Candidate;
import com.plantorganizer.cli.Text;

import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;
import tools.jackson.databind.JsonNode;

/** F01: identify a plant from photos (Pl@ntNet) or a description (the agent), then pick the right match. */
@Command(name = "/identify", separator = " ",
        description = "Identify a plant from photos or a description, then pick the right match.",
        subcommands = {IdentifyCommand.Photo.class, IdentifyCommand.Describe.class, IdentifyCommand.Select.class})
public class IdentifyCommand extends GroupCommand {

    /** Below this confidence the CLI suggests more photos or a better description. */
    static final double LOW_CONFIDENCE = 0.25;
    static final int SHOWN = 3;

    @Command(name = "photo", separator = " ", description = "Identify a plant from 1 to 5 photos (uses Pl@ntNet).")
    static class Photo extends CliCommand {

        @Parameters(arity = "1..*", paramLabel = "<file>",
                description = "Photo files, e.g. samplephotos/monstera.jpg. Several photos of the same plant "
                        + "(leaf, flower, whole plant) give a better match.")
        List<String> files;

        Photo(Session session) {
            super(session);
        }

        @Override
        protected void execute() {
            if (files.size() > AgentClient.MAX_PHOTOS) {
                throw new CliException("You can send at most " + AgentClient.MAX_PHOTOS + " photos at a time.");
            }
            List<AgentClient.Part> parts = new ArrayList<>();
            for (String file : files) {
                parts.add(AgentClient.Part.file("photos", AgentClient.checkUpload(file)));
            }
            status("Identifying your plant with Pl@ntNet");
            showMatches(session, api().postMultipart("/api/identify", parts), true);
        }
    }

    @Command(name = "text", separator = " ", description = "Identify a plant from a description (uses the AI agent).")
    static class Describe extends CliCommand {

        @Parameters(index = "0", paramLabel = "<description>",
                description = "What the plant looks like, in quotes, e.g. \"big glossy leaves with holes\".")
        String description;

        Describe(Session session) {
            super(session);
        }

        @Override
        protected void execute() {
            if (description.isBlank()) {
                throw new CliException("Describe the plant, e.g. /identify text \"big glossy leaves with holes\".");
            }
            status("Asking the agent which plant matches");
            showMatches(session, api().post("/api/identify/text", Json.body("description", description)), false);
        }
    }

    @Command(name = "select", separator = " ",
            description = "Pick one of the plants the last /identify found, by number or by name.")
    static class Select extends CliCommand {

        @Parameters(index = "0", paramLabel = "<1-3 | name>", description = "The match's number, or its name in quotes.")
        String choice;

        Select(Session session) {
            super(session);
        }

        @Override
        protected void execute() {
            List<Candidate> candidates = session.identified();
            if (candidates.isEmpty()) {
                throw new CliException("No plants have been identified. Use /identify photo <file> or "
                        + "/identify text \"<description>\" first.");
            }
            Candidate chosen = pick(candidates, choice.strip());
            status("Looking up how to care for " + chosen.displayName());
            JsonNode report = api().post("/api/identify/confirm",
                    Json.body("scientificName", chosen.scientificName(), "commonName", chosen.commonName()));
            session.setIdentified(List.of());
            JsonNode profile = report.path("profile");
            print(chosen.displayName());
            String similar = Json.str(profile, "similarPlantName");
            if (similar != null) {
                print("Heads up: no care data was found for this exact species, so this describes a similar plant ("
                        + similar + ").");
            }
            print("");
            print(Text.plain(Json.str(report, "explanation")));
            print("");
            print("Care summary:");
            print(Views.care(profile));
            String source = Views.source(profile);
            if (source != null) {
                print(source);
            }
            String add = quote(chosen.scientificName())
                    + (chosen.commonName() != null ? " --common-name " + quote(chosen.commonName()) : "");
            print("");
            print("What next?");
            print("  /plants add " + add + "     (you own it)");
            print("  /wishlist add " + add + "   (you'd like to get it)");
        }

        private static Candidate pick(List<Candidate> candidates, String choice) {
            if (choice.matches("\\d+")) {
                int n = Integer.parseInt(choice);
                if (n < 1 || n > candidates.size()) {
                    throw new CliException("Choose a number from 1 to " + candidates.size() + ".");
                }
                return candidates.get(n - 1);
            }
            String wanted = choice.toLowerCase(Locale.ROOT);
            List<Candidate> matches = candidates.stream().filter(c -> c.scientificName().toLowerCase(Locale.ROOT).contains(wanted)
                    || (c.commonName() != null && c.commonName().toLowerCase(Locale.ROOT).contains(wanted))).toList();
            if (matches.size() != 1) {
                throw new CliException((matches.isEmpty() ? "None" : "More than one") + " of the identified plants "
                        + (matches.isEmpty() ? "is called" : "matches") + " \"" + choice + "\". Choose by number (1 to "
                        + candidates.size() + ") or use the full name.");
            }
            return matches.get(0);
        }
    }

    /** Prints the top matches with their confidence and remembers them for /identify select. */
    static void showMatches(Session session, JsonNode result, boolean fromPhotos) {
        List<JsonNode> found = Json.list(result.path("candidates"));
        List<Candidate> candidates = found.stream().limit(SHOWN).map(c -> new Candidate(Json.str(c, "scientificName"),
                Json.strings(c.path("commonNames")).stream().findFirst().orElse(null), c.path("score").asDouble())).toList();
        session.setIdentified(candidates);
        List<String> notes = Json.strings(result.path("notes"));
        if (candidates.isEmpty()) {
            session.console().print(fromPhotos
                    ? "No plant could be identified from " + Text.count(result.path("photosUsed").asInt(), "photo")
                      + ". Try again with more photos (up to " + AgentClient.MAX_PHOTOS + ") from different angles: "
                      + "the whole plant, a close-up of a leaf, and a flower or fruit if it has one."
                    : "The agent couldn't match that description to a plant. Add more detail (leaf shape and colour, "
                      + "size, flowers, where it grows) or try /identify photo <file>.");
            notes.forEach(note -> session.console().print("  Note: " + note));
            return;
        }
        List<String[]> rows = new ArrayList<>();
        for (int i = 0; i < candidates.size(); i++) {
            rows.add(new String[] {(i + 1) + ".", candidates.get(i).displayName(),
                    Text.percent(candidates.get(i).score()) + " confidence"});
        }
        session.console().print(fromPhotos
                ? "Best matches from " + Text.count(result.path("photosUsed").asInt(), "photo") + ":"
                : "Best matches for your description:");
        session.console().print(Text.table(rows, "  "));
        // A terminal can't show the reference photos, so it gives their links (Cmd/Ctrl+click opens one).
        for (int i = 0; i < candidates.size(); i++) {
            String photo = Json.str(found.get(i), "referenceImageUrl");
            if (photo != null) {
                session.console().print("  Photo of " + (i + 1) + ": " + photo);
            }
        }
        notes.forEach(note -> session.console().print("  Note: " + note));
        if (candidates.get(0).score() < LOW_CONFIDENCE) {
            session.console().print(fromPhotos
                    ? "Pl@ntNet isn't confident about these. For a better match, try again with more photos (up to "
                      + AgentClient.MAX_PHOTOS + "): the whole plant, a close-up of a leaf, a flower or fruit."
                    : "The agent isn't confident about these. Add more detail to your description, or use /identify photo.");
        }
        session.console().print("Pick one with /identify select <1-" + candidates.size() + "> or /identify select \"<name>\".");
    }
}
