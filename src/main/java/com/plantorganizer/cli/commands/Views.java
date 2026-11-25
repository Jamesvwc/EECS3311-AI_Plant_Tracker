package com.plantorganizer.cli.commands;

import java.util.ArrayList;
import java.util.List;

import com.plantorganizer.cli.Json;
import com.plantorganizer.cli.Text;

import tools.jackson.databind.JsonNode;

/** Plain-text layouts for server results that several commands print. */
final class Views {

    private Views() {
    }

    /** A care profile (PlantCareProfile) as label/value rows; empty fields show "Unknown". */
    static String care(JsonNode care) {
        List<String[]> rows = new ArrayList<>();
        rows.add(row("Watering:", Json.str(care, "watering")));
        rows.add(row("How often:", Json.str(care, "wateringFrequency")));
        rows.add(row("Sunlight:", String.join(", ", Json.strings(care.path("sunlight")))));
        rows.add(row("Care level:", Json.str(care, "careLevel")));
        rows.add(row("Growth rate:", Json.str(care, "growthRate")));
        rows.add(row("Indoor plant:", yesNo(care.path("indoor"))));
        rows.add(row("Toxic to pets:", yesNo(care.path("poisonousToPets"))));
        rows.add(row("Toxic to humans:", yesNo(care.path("poisonousToHumans"))));
        rows.add(row("Hardiness zones:", Json.str(care, "hardinessZones")));
        rows.add(row("Family:", Json.str(care, "family")));
        String description = Json.str(care, "description");
        return Text.table(rows, "  ") + (description != null ? "\n  Description:\n" + Text.indent(description, "    ") : "");
    }

    /** Where care data came from (InfoSource), so the user can judge it. */
    static String source(JsonNode care) {
        String source = Json.str(care, "source");
        if (source == null) {
            return null;
        }
        return "Source: " + switch (source) {
            case "PERENUAL" -> "Perenual plant database";
            case "AI_GENERATED" -> "AI-generated from web search results (lower reliability, please double-check)";
            default -> Text.words(source);
        };
    }

    /** F02 schedule result: interval, next waterings, how it was made, data warnings. */
    static String schedule(JsonNode s, String plantId) {
        StringBuilder sb = new StringBuilder();
        sb.append("Watering schedule: every ").append(s.path("intervalDays").asInt()).append(" days");
        String basis = Json.str(s, "intervalBasis");
        if (basis != null) {
            sb.append(" (").append(basis).append(')');
        }
        if (Json.bool(s, "grouped") && !Json.strings(s.path("groupedWith")).isEmpty()) {
            sb.append("\nGrouped with ").append(String.join(", ", Json.strings(s.path("groupedWith"))))
                    .append(" so you can water them together.");
        }
        List<String> next = Json.strings(s.path("nextWaterings")).stream().limit(3).map(Text::when).toList();
        if (!next.isEmpty()) {
            sb.append("\nNext waterings: ").append(String.join("; ", next));
        }
        String explanation = Json.str(s, "explanation");
        if (explanation != null) {
            sb.append("\n\n").append(Text.plain(explanation));
        }
        String warning = Json.str(s, "dataWarning");
        if (warning != null) {
            sb.append("\n\nNote: ").append(Text.plain(warning))
                    .append("\nSearch the web for better watering info with /plants research ").append(plantId).append('.');
        }
        return sb.toString();
    }

    /** An AddPlantResult (/plants add, /wishlist bought). */
    static String addedPlant(JsonNode r) {
        String id = "P" + r.path("plantId").asLong();
        StringBuilder sb = new StringBuilder("Added ").append(Json.str(r, "plantName")).append(" to your plants as ")
                .append(id).append('.');
        String location = Json.str(r, "location");
        if (location != null) {
            sb.append(" It's in ").append(location).append('.');
        }
        JsonNode schedule = r.path("schedule");
        if (!schedule.isMissingNode() && !schedule.isNull()) {
            sb.append("\n").append(schedule(schedule, id));
            if (Json.bool(schedule, "askAboutNotifications")) {
                sb.append("\n\nWould you like watering reminders? Turn them on with /notifications on ").append(id)
                        .append(" (or /notifications on for all your plants).");
            }
        }
        for (JsonNode note : r.path("notes")) {
            sb.append("\nNote: ").append(note.asString());
        }
        return sb.toString();
    }

    /** A ResearchResult: the agent's answer plus the pages it used. */
    static String research(JsonNode r) {
        StringBuilder sb = new StringBuilder(Text.plain(Json.str(r, "answer")));
        List<JsonNode> sources = Json.list(r.path("sources"));
        if (!sources.isEmpty()) {
            sb.append("\nSources:");
            int i = 1;
            for (JsonNode s : sources) {
                sb.append("\n  [").append(i++).append("] ").append(Text.orUnknown(Json.str(s, "title")));
                String url = Json.str(s, "url");
                if (url != null) {
                    sb.append(" - ").append(url);
                }
            }
        }
        return sb.toString();
    }

    static String yesNo(JsonNode value) {
        if (value.isMissingNode() || value.isNull()) {
            return null;
        }
        return value.asBoolean() ? "Yes" : "No";
    }

    private static String[] row(String label, String value) {
        return new String[] {label, Text.orUnknown(value)};
    }
}
