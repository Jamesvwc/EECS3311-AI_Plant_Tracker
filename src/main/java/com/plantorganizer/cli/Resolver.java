package com.plantorganizer.cli;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;
import java.util.stream.Stream;

import tools.jackson.databind.JsonNode;

/**
 * Turns what the user typed ("Monty", "monstera", "P3") into the record the
 * server knows by ID. The CLI fetches the user's list, matches the name here,
 * then calls the endpoint with the ID, so the server needs no name lookup.
 *
 * IDs carry a letter so they can't be confused with counts: P = owned plant,
 * W = wishlist item, R = recommendation. Names match exactly first (nickname,
 * common or scientific name, ignoring case), then partially ("monstera" finds
 * Monstera deliciosa). A name that fits several records is an error that lists
 * their IDs; one that fits none suggests the closest name.
 */
public final class Resolver {

    private final AgentClient api;
    private final Session session;

    Resolver(AgentClient api, Session session) {
        this.api = api;
        this.session = session;
    }

    // ------------------------------------------------------------------ owned plants

    public List<JsonNode> ownedPlants() {
        return Json.list(api.get("/api/plants"));
    }

    /** @throws CliException "you do not own a "x" plant" (with a suggestion) or "several match" */
    public JsonNode ownedPlant(String ref) {
        return match(ownedPlants(), ref, "P", Resolver::plantNames, Resolver::plantLabel,
                "You do not own a \"" + ref + "\" plant.", "Run /plants list to see your plants and their IDs.");
    }

    public static String plantId(JsonNode plant) {
        return "P" + Json.id(plant);
    }

    /** P3 "Monty" (Swiss cheese plant), or P7 Swiss cheese plant when there is no nickname. */
    public static String plantLabel(JsonNode plant) {
        String nickname = Json.str(plant, "nickname");
        String species = speciesName(plant);
        return plantId(plant) + (nickname != null ? " \"" + nickname + "\" (" + species + ")" : " " + species);
    }

    /** What the user calls a plant: its nickname, else its common name, else its scientific name. */
    public static String plantName(JsonNode plant) {
        String nickname = Json.str(plant, "nickname");
        return nickname != null ? nickname : speciesName(plant);
    }

    public static String speciesName(JsonNode plant) {
        String common = Json.str(plant, "commonName");
        return common != null ? common : Json.str(plant, "scientificName");
    }

    private static List<String> plantNames(JsonNode p) {
        return names(Json.str(p, "nickname"), Json.str(p, "commonName"), Json.str(p, "scientificName"), Json.str(p, "name"));
    }

    // ------------------------------------------------------------------ wishlist

    public List<JsonNode> wishlist() {
        return Json.list(api.get("/api/desired"));
    }

    public JsonNode wishlistItem(String ref) {
        return match(wishlist(), ref, "W", p -> names(Json.str(p, "commonName"), Json.str(p, "scientificName"),
                        Json.str(p, "displayName")), Resolver::wishlistLabel,
                "There is no \"" + ref + "\" on your wishlist.", "Run /wishlist list to see your wishlist and its IDs.");
    }

    public static String wishlistLabel(JsonNode item) {
        return "W" + Json.id(item) + " " + displayName(item);
    }

    // ------------------------------------------------------------------ recommendations

    /**
     * Kept recommendations plus the ones the last /recommend ask suggested.
     *
     * @param preferSuggested look among the last suggestions first (for /recommend keep: the same species
     *                        may also be on the list already, and the user means the one just suggested)
     */
    public JsonNode recommendation(String ref, boolean preferSuggested) {
        Function<JsonNode, List<String>> names = r -> names(Json.str(r, "commonName"), Json.str(r, "scientificName"),
                Json.str(r, "displayName"));
        String notFound = "There is no \"" + ref + "\" among your recommendations.";
        String hint = "Run /recommend list to see them and their IDs.";
        if (preferSuggested && !session.suggestedRecommendations().isEmpty()) {
            try {
                return match(session.suggestedRecommendations(), ref, "R", names, Resolver::recommendationLabel, notFound, hint);
            } catch (CliException e) {
                // not among the suggestions: look in the whole list below
            }
        }
        List<JsonNode> all = new ArrayList<>(Json.list(api.get("/api/recommendations").path("items")));
        for (JsonNode suggested : session.suggestedRecommendations()) {
            if (all.stream().noneMatch(r -> Json.id(r) == Json.id(suggested))) {
                all.add(suggested);
            }
        }
        return match(all, ref, "R", names, Resolver::recommendationLabel, notFound, hint);
    }

    public static String recommendationLabel(JsonNode rec) {
        return "R" + Json.id(rec) + " " + displayName(rec);
    }

    /** "Spider plant (Chlorophytum comosum)" for wishlist items and recommendations. */
    public static String displayName(JsonNode item) {
        String common = Json.str(item, "commonName");
        String scientific = Json.str(item, "scientificName");
        return common != null && !common.equalsIgnoreCase(scientific) ? common + " (" + scientific + ")" : scientific;
    }

    // ------------------------------------------------------------------ spaces

    public List<JsonNode> spaces() {
        return Json.list(api.get("/api/spaces"));
    }

    public JsonNode room(String name) {
        List<JsonNode> rooms = spaces().stream().filter(s -> "ROOM".equals(Json.str(s, "kind"))).toList();
        return match(rooms, name, null, s -> names(Json.str(s, "name")), s -> "\"" + Json.str(s, "name") + "\"",
                "No room called \"" + name + "\".", "Run /spaces list to see your rooms, or create it with /spaces add-room.");
    }

    public JsonNode spot(JsonNode room, String name) {
        List<JsonNode> spots = descendants(room).filter(s -> "SPOT".equals(Json.str(s, "kind"))).toList();
        return match(spots, name, null, s -> names(Json.str(s, "name")), s -> "\"" + Json.str(s, "path") + "\"",
                "No spot called \"" + name + "\" in " + Json.str(room, "name") + ".",
                "Run /spaces list to see its spots, or create it with /spaces add-spot.");
    }

    /** A room, or the spot inside it when {@code spotName} is given. */
    public JsonNode space(String roomName, String spotName) {
        JsonNode room = room(roomName);
        return spotName == null ? room : spot(room, spotName);
    }

    public static Stream<JsonNode> descendants(JsonNode space) {
        return Json.list(space.path("children")).stream().flatMap(c -> Stream.concat(Stream.of(c), descendants(c)));
    }

    // ------------------------------------------------------------------ friends

    public JsonNode friend(String ref) {
        List<JsonNode> friends = Json.list(api.get("/api/friends"));
        String r = ref.strip();
        if (r.matches("\\d+")) {
            long id = Long.parseLong(r);
            return friends.stream().filter(f -> Json.id(f) == id).findFirst()
                    .orElseThrow(() -> new CliException("No friend with user ID " + r + ". Run /friends list to see your friends."));
        }
        return match(friends, ref, null, f -> names(Json.str(f, "name")), f -> Json.str(f, "name") + " (user " + Json.id(f) + ")",
                "No friend with name " + ref + ".", "Run /friends list to see your friends.");
    }

    // ------------------------------------------------------------------ matching

    private static List<String> names(String... names) {
        return Stream.of(names).filter(n -> n != null && !n.isBlank()).map(n -> n.toLowerCase(Locale.ROOT).strip()).toList();
    }

    static JsonNode match(List<JsonNode> items, String ref, String idPrefix, Function<JsonNode, List<String>> names,
                          Function<JsonNode, String> label, String notFound, String listHint) {
        String r = ref == null ? "" : ref.strip();
        if (r.isEmpty()) {
            throw new CliException("Give a name" + (idPrefix != null ? " or an ID such as " + idPrefix + "3" : "") + ".");
        }
        if (idPrefix != null && r.matches("(?i)" + idPrefix + "\\d+")) {
            long id = Long.parseLong(r.substring(1));
            return items.stream().filter(i -> Json.id(i) == id).findFirst()
                    .orElseThrow(() -> new CliException("Nothing has the ID " + idPrefix + id + ". " + listHint));
        }
        if (idPrefix != null && r.matches("\\d+")) {
            throw new CliException("IDs start with " + idPrefix + ", e.g. " + idPrefix + r + ". " + listHint);
        }
        String n = r.toLowerCase(Locale.ROOT).replaceAll("^(my|the|a|an)\\s+", "").strip();
        List<JsonNode> exact = items.stream().filter(i -> names.apply(i).contains(n)).toList();
        if (exact.size() == 1) {
            return exact.get(0);
        }
        if (exact.size() > 1) {
            throw ambiguous(r, exact, label);
        }
        List<JsonNode> partial = items.stream().filter(i -> names.apply(i).stream()
                .anyMatch(name -> name.contains(n) || name.split(" ")[0].equals(n))).toList();
        if (partial.size() == 1) {
            return partial.get(0);
        }
        if (partial.size() > 1) {
            throw ambiguous(r, partial, label);
        }
        String closest = closest(items, n, names, label);
        throw new CliException(notFound + (closest != null ? " Did you mean " + closest + "?" : "") + " " + listHint);
    }

    private static CliException ambiguous(String ref, List<JsonNode> matches, Function<JsonNode, String> label) {
        return new CliException("\"" + ref + "\" matches more than one: "
                + String.join(", ", matches.stream().map(label).toList()) + ". Use the ID or the full name instead.");
    }

    /** The item whose name is closest to what was typed (a typo), or null when nothing is close. */
    private static String closest(List<JsonNode> items, String typed, Function<JsonNode, List<String>> names,
                                  Function<JsonNode, String> label) {
        JsonNode best = null;
        int bestDistance = Integer.MAX_VALUE;
        for (JsonNode item : items) {
            for (String name : names.apply(item)) {
                int d = distance(typed, name);
                if (d < bestDistance) {
                    best = item;
                    bestDistance = d;
                }
            }
        }
        int allowed = Math.max(2, typed.length() / 4);
        return best != null && bestDistance <= allowed ? label.apply(best) : null;
    }

    /** Levenshtein edit distance. */
    static int distance(String a, String b) {
        int[] prev = new int[b.length() + 1];
        int[] cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            prev[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] t = prev;
            prev = cur;
            cur = t;
        }
        return prev[b.length()];
    }
}
