package com.plantorganizer.cli;

import static com.plantorganizer.cli.CommandLineSplitter.quote;

import java.util.Map;

import tools.jackson.databind.JsonNode;

/**
 * Turns the server's {@code SuggestedAction} (the planner's intent plus the
 * plant, space and details it extracted) into the structured command that
 * does it. This mapping lives in the CLI so the server stays independent of
 * the CLI's command syntax. Plant names are replaced by their P-ID when they
 * match exactly one plant, so the suggested command is unambiguous.
 */
public final class CommandSuggestions {

    /** The command's shape, shown when the agent's suggestion lacks a value the command needs. */
    private static final Map<String, String> TEMPLATES = Map.ofEntries(
            Map.entry("IDENTIFY", "/identify text \"<description>\"  or  /identify photo <file>"),
            Map.entry("ADD_OWNED", "/plants add \"<scientific name>\""),
            Map.entry("ADD_DESIRED", "/wishlist add \"<scientific name>\""),
            Map.entry("HEALTH_CHECK", "/health check <plant> --describe \"<problem>\" [--photo <file>]"),
            Map.entry("UPDATE_AVAILABILITY", "/calendar update \"<when you're free>\"  or  /calendar import <file>"),
            Map.entry("UNDO_SCHEDULE", "/calendar undo"),
            Map.entry("RECOMMEND", "/recommend ask \"<what you're looking for>\""),
            Map.entry("REFRESH_RECOMMENDATIONS", "/recommend auto"),
            Map.entry("PLACE_PLANT", "/spaces place \"<room>\" \"<spot>\" <plant>"),
            Map.entry("WATERING_STATUS", "/notifications list"),
            Map.entry("MARK_WATERED", "/plants water <plant>"),
            Map.entry("NOTIFICATIONS_ON", "/notifications on [<plant>]"),
            Map.entry("NOTIFICATIONS_OFF", "/notifications off [<plant>]"),
            Map.entry("CHECK_FIT", "/wishlist fit <wishlist item> --room \"<room>\" [--spot \"<spot>\"]"),
            Map.entry("UPDATE_CARE_NOTES", "/plants care <plant> --watering <amount> --frequency \"<how often>\" ... (see /help plants care)"),
            Map.entry("ADD_NOTE", "/plants note <plant> \"<note>\""));

    private CommandSuggestions() {
    }

    /**
     * The command for this suggestion, or null when it needs a value the
     * agent didn't find (use {@link #template}) or when it is only a question.
     */
    public static String toCommand(JsonNode suggestion, Resolver find) {
        String intent = Json.str(suggestion, "intent");
        String plant = Json.str(suggestion, "plantName");
        String space = Json.str(suggestion, "spaceName");
        String details = Json.str(suggestion, "details");
        if (intent == null) {
            return null;
        }
        return switch (intent) {
            case "IDENTIFY" -> details != null ? "/identify text " + quote(details) : null;
            case "ADD_OWNED" -> plant != null ? "/plants add " + quote(plant) : null;
            case "ADD_DESIRED" -> plant != null ? "/wishlist add " + quote(plant) : null;
            case "HEALTH_CHECK" -> plant != null
                    ? "/health check " + ownedRef(plant, find) + (details != null ? " --describe " + quote(details) : "") : null;
            case "UPDATE_AVAILABILITY" -> details != null ? "/calendar update " + quote(details) : null;
            case "UNDO_SCHEDULE" -> "/calendar undo";
            case "RECOMMEND" -> details != null ? "/recommend ask " + quote(details) : "/recommend auto";
            case "REFRESH_RECOMMENDATIONS" -> "/recommend auto";
            case "PLACE_PLANT" -> plant != null && space != null ? place(plant, space, find) : null;
            case "WATERING_STATUS" -> "/notifications list";
            case "MARK_WATERED" -> plant != null ? "/plants water " + ownedRef(plant, find) : null;
            case "NOTIFICATIONS_ON" -> "/notifications on" + (plant != null ? " " + ownedRef(plant, find) : "");
            case "NOTIFICATIONS_OFF" -> "/notifications off" + (plant != null ? " " + ownedRef(plant, find) : "");
            case "CHECK_FIT" -> plant != null
                    ? "/wishlist fit " + wishlistRef(plant, find) + (space != null ? " --room " + quote(space) : "") : null;
            case "ADD_NOTE" -> plant != null && details != null
                    ? "/plants note " + ownedRef(plant, find) + " " + quote(details) : null;
            default -> null; // GENERAL, CARE_QUESTION (questions) and UPDATE_CARE_NOTES (needs explicit fields)
        };
    }

    /** The shape of the command for this intent, or null for questions. */
    public static String template(JsonNode suggestion) {
        String intent = Json.str(suggestion, "intent");
        return intent == null ? null : TEMPLATES.get(intent);
    }

    private static String ownedRef(String name, Resolver find) {
        try {
            return Resolver.plantId(find.ownedPlant(name));
        } catch (CliException e) {
            return quote(name);
        }
    }

    private static String wishlistRef(String name, Resolver find) {
        try {
            return "W" + Json.id(find.wishlistItem(name));
        } catch (CliException e) {
            return quote(name);
        }
    }

    /** "/spaces place room spot plant" when the space the agent heard is one spot of one room. */
    private static String place(String plant, String spaceName, Resolver find) {
        String wanted = spaceName.toLowerCase(java.util.Locale.ROOT);
        for (JsonNode room : find.spaces()) {
            for (JsonNode spot : Resolver.descendants(room).filter(s -> "SPOT".equals(Json.str(s, "kind"))).toList()) {
                String name = Json.str(spot, "name").toLowerCase(java.util.Locale.ROOT);
                String path = Json.str(spot, "path").toLowerCase(java.util.Locale.ROOT);
                if (wanted.equals(name) || wanted.contains(name) || path.contains(wanted)) {
                    return "/spaces place " + quote(Json.str(room, "name")) + " " + quote(Json.str(spot, "name"))
                            + " " + ownedRef(plant, find);
                }
            }
        }
        return null;
    }
}
