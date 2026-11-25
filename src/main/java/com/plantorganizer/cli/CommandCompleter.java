package com.plantorganizer.cli;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.jline.reader.Candidate;
import org.jline.reader.CompletingParsedLine;
import org.jline.reader.Completer;
import org.jline.reader.LineReader;
import org.jline.reader.ParsedLine;

import com.plantorganizer.cli.CommandLineSplitter.Word;
import com.plantorganizer.cli.commands.Commands;
import com.plantorganizer.cli.commands.FriendsCommand;
import com.plantorganizer.cli.commands.PlantsCommand;
import com.plantorganizer.cli.commands.RecommendCommand;
import com.plantorganizer.cli.commands.WishlistCommand;

import picocli.CommandLine;
import picocli.CommandLine.Model.ArgSpec;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Model.OptionSpec;
import picocli.CommandLine.Model.PositionalParamSpec;
import tools.jackson.databind.JsonNode;

/**
 * Tab completion at the prompt (used by {@link TerminalInput}). What it offers
 * comes from the same picocli command definitions that /help shows, so it
 * stays in step with the commands:
 *
 *   - the first word: the commands (/plants, /wishlist...);
 *   - after a noun: its verbs (list, add, water...);
 *   - after "-": the verb's options (--nickname, --photo...), also offered
 *     once the required values are given;
 *   - a value: what fits there, looked up on the server when needed: your
 *     plants (IDs, or names once you start typing one), wishlist items,
 *     recommendations, rooms and spots, friends, users, the last /identify
 *     matches, files for photos, and fixed choices such as yes/no.
 *
 * Free-text values (a description, a note, a new name) have nothing to offer.
 * Values with spaces are completed in quotes.
 */
public final class CommandCompleter implements Completer {

    private final Session session;
    private final Map<Class<?>, CommandSpec> specs = new HashMap<>();

    public CommandCompleter(Session session) {
        this.session = session;
    }

    @Override
    public void complete(LineReader reader, ParsedLine line, List<Candidate> candidates) {
        try {
            String typed = line.word().substring(0, Math.min(line.wordCursor(), line.word().length()));
            candidates.addAll(candidates(line.words(), line.wordIndex(), typed));
        } catch (RuntimeException e) {
            // nothing to offer (e.g. the server stopped answering): Tab does nothing
        }
    }

    /** What can go in {@code words[index]}, which so far is {@code typed}. */
    List<Candidate> candidates(List<String> words, int index, String typed) {
        if (index == 0) {
            return commands("/");
        }
        CommandSpec spec = command(words.get(0));
        if (spec == null) {
            return List.of();
        }
        int i = 1;
        while (!spec.subcommands().isEmpty()) { // a noun: the next word is one of its verbs
            if (i == index) {
                return verbs(spec);
            }
            CommandLine verb = spec.subcommands().get(words.get(i));
            if (verb == null) {
                return List.of();
            }
            spec = verb.getCommandSpec();
            i++;
        }
        return arguments(spec, words.subList(i, index), typed);
    }

    private CommandSpec command(String word) {
        Class<?> command = word.startsWith("/") ? Commands.find(word.substring(1).toLowerCase(Locale.ROOT)) : null;
        return command == null ? null
                : specs.computeIfAbsent(command, c -> session.dispatcher().commandLine(c).getCommandSpec());
    }

    private List<Candidate> commands(String prefix) {
        List<Candidate> list = new ArrayList<>();
        for (String name : Commands.all().keySet()) {
            list.add(value(prefix + name, summary(command("/" + name).usageMessage().description()), list.size()));
        }
        return list;
    }

    private static List<Candidate> verbs(CommandSpec noun) {
        List<Candidate> list = new ArrayList<>();
        noun.subcommands().forEach((name, verb) ->
                list.add(value(name, summary(verb.getCommandSpec().usageMessage().description()), list.size())));
        return list;
    }

    // ------------------------------------------------------------------ after the verb

    /** What has been typed after the verb: values in order, and the options given so far with their values. */
    private record Given(CommandSpec spec, List<String> values, Map<String, String> options) {

        /** The value given for the parameter labelled {@code label} (e.g. the room before a spot), or null. */
        String valueOf(String label) {
            for (PositionalParamSpec p : spec.positionalParameters()) {
                if (label.equals(p.paramLabel()) && p.index().min() < values.size()) {
                    return values.get(p.index().min());
                }
            }
            return null;
        }
    }

    private List<Candidate> arguments(CommandSpec spec, List<String> before, String typed) {
        List<String> values = new ArrayList<>();
        Map<String, String> optionValues = new HashMap<>();
        Set<OptionSpec> used = new HashSet<>();
        OptionSpec awaiting = null; // an option just typed, whose value comes next
        for (String word : before) {
            if (awaiting != null) {
                optionValues.put(awaiting.longestName(), word);
                awaiting = null;
                continue;
            }
            OptionSpec option = word.startsWith("-") ? spec.findOption(word) : null;
            if (option == null) {
                values.add(word);
            } else {
                used.add(option);
                awaiting = option.arity().max() > 0 ? option : null;
            }
        }
        Given given = new Given(spec, values, optionValues);
        if (awaiting != null) {
            return valuesFor(awaiting, given, typed);
        }
        if (typed.startsWith("-")) {
            return options(spec, used, true);
        }
        PositionalParamSpec next = spec.positionalParameters().stream()
                .filter(p -> p.index().contains(values.size())).findFirst().orElse(null);
        List<Candidate> list = new ArrayList<>(next == null ? List.of() : valuesFor(next, given, typed));
        boolean required = next != null && next.arity().min() > 0 && values.size() == next.index().min();
        if (!required) {
            list.addAll(options(spec, used, false));
        }
        return list;
    }

    private static List<Candidate> options(CommandSpec spec, Set<OptionSpec> used, boolean withHelp) {
        List<Candidate> list = new ArrayList<>();
        for (OptionSpec option : spec.options()) {
            boolean help = option.usageHelp() || option.versionHelp();
            if ((used.contains(option) && !option.isMultiValue()) || (help && !withHelp)) {
                continue;
            }
            String name = option.longestName();
            String shown = option.arity().max() > 0 ? name + " " + option.paramLabel() : name;
            list.add(new Candidate(name, shown, null, summary(option.description()), null, null, true, 1000 + list.size()));
        }
        return list;
    }

    /** What fits a parameter or an option's value. */
    private List<Candidate> valuesFor(ArgSpec arg, Given given, String typed) {
        if (arg.completionCandidates() != null) {
            List<Candidate> list = new ArrayList<>();
            boolean isEnum = arg.type().isEnum();
            for (String choice : arg.completionCandidates()) {
                list.add(value(isEnum ? choice.toLowerCase(Locale.ROOT) : choice, null, list.size()));
            }
            return list;
        }
        String label = arg.paramLabel();
        if (given.spec().name().equals("add-" + label.replaceAll("[<>]", ""))) {
            return List.of(); // the name of a new room or spot
        }
        return switch (label) {
            case PlantsCommand.PLANT -> plants(typed);
            case WishlistCommand.ITEM -> wishlist(typed);
            case RecommendCommand.REC -> recommendations(typed);
            case FriendsCommand.FRIEND -> friends(typed);
            case "<user id>" -> users();
            case "<room>" -> rooms();
            case "<spot>" -> spots(given.options().getOrDefault("--room", given.valueOf("<room>")));
            case "<file>" -> files(typed);
            case "<1-3 | name>" -> identified(typed);
            case "<command>" -> given.values().isEmpty() ? commands("")
                    : given.values().size() == 1 && command("/" + given.values().get(0)) != null
                            ? verbs(command("/" + given.values().get(0))) : List.of();
            default -> List.of();
        };
    }

    // ------------------------------------------------------------------ values from the server

    private List<Candidate> plants(String typed) {
        List<JsonNode> plants = session.find().ownedPlants();
        if (typed.isEmpty() || typed.matches("(?i)P\\d*")) {
            return ids(plants, Resolver::plantId, p -> Resolver.plantLabel(p).substring(Resolver.plantId(p).length() + 1));
        }
        return names(plants, Resolver::plantId, "nickname", "commonName", "scientificName");
    }

    private List<Candidate> wishlist(String typed) {
        List<JsonNode> items = session.find().wishlist();
        if (typed.isEmpty() || typed.matches("(?i)W\\d*")) {
            return ids(items, i -> "W" + Json.id(i), Resolver::displayName);
        }
        return names(items, i -> "W" + Json.id(i), "commonName", "scientificName");
    }

    private List<Candidate> recommendations(String typed) {
        Map<Long, JsonNode> recs = new LinkedHashMap<>();
        Json.list(session.api().get("/api/recommendations").path("items")).forEach(r -> recs.put(Json.id(r), r));
        session.suggestedRecommendations().forEach(r -> recs.putIfAbsent(Json.id(r), r));
        List<JsonNode> all = List.copyOf(recs.values());
        if (typed.isEmpty() || typed.matches("(?i)R\\d*")) {
            return ids(all, r -> "R" + Json.id(r), Resolver::displayName);
        }
        return names(all, r -> "R" + Json.id(r), "commonName", "scientificName");
    }

    private List<Candidate> friends(String typed) {
        List<JsonNode> friends = Json.list(session.api().get("/api/friends"));
        if (typed.matches("\\d+")) {
            return ids(friends, f -> String.valueOf(Json.id(f)), f -> Json.str(f, "name"));
        }
        return names(friends, f -> "user " + Json.id(f), "name");
    }

    private List<Candidate> users() {
        return ids(Json.list(session.api().get("/api/users")), u -> String.valueOf(Json.id(u)), u -> Json.str(u, "displayName"));
    }

    private List<Candidate> rooms() {
        List<JsonNode> rooms = session.find().spaces().stream().filter(s -> "ROOM".equals(Json.str(s, "kind"))).toList();
        return names(rooms, r -> null, "name");
    }

    /** The spots in the given room, or in every room when no room is given yet. */
    private List<Candidate> spots(String roomName) {
        Stream<JsonNode> rooms = session.find().spaces().stream().filter(s -> "ROOM".equals(Json.str(s, "kind")));
        if (roomName != null) {
            rooms = rooms.filter(r -> roomName.equalsIgnoreCase(Json.str(r, "name")));
        }
        List<JsonNode> spots = rooms.flatMap(Resolver::descendants).filter(s -> "SPOT".equals(Json.str(s, "kind"))).toList();
        return names(spots, s -> Json.str(s, "path"), "name");
    }

    private List<Candidate> identified(String typed) {
        List<Session.Candidate> found = session.identified();
        List<Candidate> list = new ArrayList<>();
        for (int i = 0; i < found.size(); i++) {
            Session.Candidate c = found.get(i);
            String number = String.valueOf(i + 1);
            if (typed.isEmpty() || Character.isDigit(typed.charAt(0))) {
                list.add(value(number, c.displayName(), i));
            } else {
                Stream.of(c.commonName(), c.scientificName()).filter(n -> n != null)
                        .forEach(n -> list.add(value(n, "match " + number, list.size())));
            }
        }
        return list;
    }

    /** One candidate per item: its ID, described by its name. */
    private static List<Candidate> ids(List<JsonNode> items, Function<JsonNode, String> id,
                                       Function<JsonNode, String> description) {
        List<Candidate> list = new ArrayList<>();
        items.stream().sorted(Comparator.comparingLong(Json::id))
                .forEach(item -> list.add(value(id.apply(item), description.apply(item), list.size())));
        return list;
    }

    /** One candidate per distinct name (the given fields of each item), described by the IDs that have it. */
    private static List<Candidate> names(List<JsonNode> items, Function<JsonNode, String> id,
                                         String... fields) {
        Map<String, Set<String>> names = new LinkedHashMap<>();
        Map<String, String> spelling = new HashMap<>();
        for (JsonNode item : items) {
            for (String field : fields) {
                String name = Json.str(item, field);
                if (name != null && !name.isBlank()) {
                    String key = name.strip().toLowerCase(Locale.ROOT);
                    spelling.putIfAbsent(key, name.strip());
                    Set<String> ids = names.computeIfAbsent(key, k -> new LinkedHashSet<>());
                    if (id.apply(item) != null) {
                        ids.add(id.apply(item));
                    }
                }
            }
        }
        List<Candidate> list = new ArrayList<>();
        names.forEach((key, ids) -> list.add(value(spelling.get(key), ids.isEmpty() ? null : String.join(", ", ids), 0)));
        return list;
    }

    // ------------------------------------------------------------------ files

    /** Files and folders starting with what was typed, relative to the folder Agent.jar runs in. */
    static List<Candidate> files(String typed) {
        int slash = Math.max(typed.lastIndexOf('/'), typed.lastIndexOf(File.separatorChar));
        String folder = typed.substring(0, slash + 1);
        String start = typed.substring(slash + 1);
        List<Candidate> list = new ArrayList<>();
        try (Stream<Path> entries = Files.list(Path.of(folder.isEmpty() ? "." : folder))) {
            entries.forEach(path -> {
                String name = path.getFileName().toString();
                if (name.startsWith(".") && !start.startsWith(".")) {
                    return;
                }
                boolean isFolder = Files.isDirectory(path);
                String shown = name + (isFolder ? "/" : "");
                // a folder isn't finished: no space after it, so its files can be completed next
                list.add(new Candidate(folder + shown, shown, null, null, null, null, !isFolder));
            });
        } catch (IOException | RuntimeException e) {
            // no such folder, or it can't be read: nothing to offer
        }
        return list;
    }

    // ------------------------------------------------------------------ helpers

    private static Candidate value(String value, String description, int order) {
        return new Candidate(value, value, null, description, null, null, true, order);
    }

    private static String summary(String[] description) {
        return description == null || description.length == 0 ? null : description[0];
    }

    /**
     * Splits the line for JLine the same way the dispatcher does (with
     * {@link CommandLineSplitter}), so completion sees the same words, and puts
     * a completed value in quotes when it has spaces or the user opened a quote.
     */
    public static final class Parser implements org.jline.reader.Parser {

        @Override
        public ParsedLine parse(String line, int cursor, ParseContext context) {
            List<Word> words = new ArrayList<>(CommandLineSplitter.words(line));
            for (int i = 0; i < words.size(); i++) {
                if (words.get(i).start() <= cursor && cursor <= words.get(i).end()) {
                    return new Line(line, cursor, words, i);
                }
            }
            // the cursor is between words, or after a space at the end: complete a new, empty word there
            int index = (int) words.stream().filter(w -> w.end() < cursor).count();
            words.add(index, new Word("", cursor, cursor));
            return new Line(line, cursor, words, index);
        }
    }

    /** One parsed line: its words and which one the cursor is in. */
    record Line(String line, int cursor, List<Word> parts, int wordIndex) implements CompletingParsedLine {

        private Word current() {
            return parts.get(wordIndex);
        }

        @Override
        public String word() {
            return current().text();
        }

        @Override
        public int wordCursor() {
            String typed = CommandLineSplitter.words(line.substring(current().start(), cursor)).stream()
                    .map(Word::text).collect(Collectors.joining());
            return Math.min(typed.length(), word().length());
        }

        @Override
        public List<String> words() {
            return parts.stream().map(Word::text).toList();
        }

        @Override
        public CharSequence escape(CharSequence candidate, boolean complete) {
            char open = current().start() < cursor ? line.charAt(current().start()) : 0;
            boolean quoted = open == '"' || open == '\'';
            String value = candidate.toString();
            if (!quoted && value.chars().noneMatch(c -> Character.isWhitespace(c) || c == '"' || c == '\'')) {
                return value;
            }
            return CommandLineSplitter.quote(value, quoted ? open : '"', complete);
        }

        @Override
        public int rawWordCursor() {
            return cursor - current().start();
        }

        @Override
        public int rawWordLength() {
            return current().end() - current().start();
        }
    }
}
