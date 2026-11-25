package com.plantorganizer.agent;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.plantorganizer.agent.tools.IdentifyPlantTool;
import com.plantorganizer.agent.tools.PlantInfoTool;
import com.plantorganizer.agent.tools.ToolType;
import com.plantorganizer.common.ExternalServiceException;
import com.plantorganizer.common.JsonValues;
import com.plantorganizer.llm.LLMClient;
import com.plantorganizer.plants.OwnedPlant;
import com.plantorganizer.websearch.ResearchResult;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Plans the agent's work in two stages:
 *
 *   1. {@link #interpret}: for a free-text chat message (the CLI), the LLM
 *      classifies the intent and extracts arguments (plant, space, details).
 *      Without an API key a keyword fallback keeps the basic commands working.
 *   2. {@link #createPlan}: for a request, chooses which tools run, in which
 *      order, and how each step's arguments come from earlier results. This
 *      part is rule-based so plans are predictable, cheap and testable.
 */
@Component
public class AgentPlanner {

    private static final Logger log = LoggerFactory.getLogger(AgentPlanner.class);

    private static final String INTERPRET_INSTRUCTIONS = """
            You are the planner of PlantOrganizer's plant-care agent. Classify the user's LATEST chat message
            into exactly one intent and extract its arguments. Intents:
            IDENTIFY - wants to know what an unknown plant is (from a description or a photo);
            CARE_QUESTION - asks how to care for, water, place or grow a plant, or any plant question;
            ADD_OWNED - says they own/bought/got a plant and want it tracked;
            ADD_DESIRED - wants a plant on their wishlist / to buy later;
            HEALTH_CHECK - describes a problem with, or asks about the health of, one of their plants;
            UPDATE_AVAILABILITY - says when they are free, busy or travelling, or asks to fit watering to their schedule;
            UNDO_SCHEDULE - wants to undo the last schedule change;
            RECOMMEND - is looking for a new plant (possibly with constraints);
            REFRESH_RECOMMENDATIONS - asks to refresh their automatic recommendations;
            PLACE_PLANT - says where one of their plants is, or moves it;
            WATERING_STATUS - asks what needs watering;
            MARK_WATERED - says they watered a plant;
            NOTIFICATIONS_ON / NOTIFICATIONS_OFF - wants watering reminders on or off;
            CHECK_FIT - asks whether a wishlist plant suits one of their spaces;
            UPDATE_CARE_NOTES - wants to change, correct, fill in or save something in the stored care notes of
            one of their plants (watering, watering frequency, sunlight, care level, growth rate, hardiness
            zones, family, description, indoor/outdoor, toxicity), e.g. "update my fern's care notes: water every 5 days";
            ADD_NOTE - wants a free-text note or observation saved to one of their plants, e.g. "add a note to my
            monstera that I repotted it today" (details = only the note text itself, without the command words);
            GENERAL - anything else.
            plantName: the plant mentioned, as written (resolve "it" from the conversation); empty if none.
            spaceName: the room or spot mentioned; empty if none.
            details: what the tool needs - the plant description, the constraints, the availability text
            or the question - in the user's words.
            preference: a lasting fact about the user's taste or home worth remembering (e.g. "has a cat",
            "loves trailing plants"); empty if none.
            """;
    private static final String INTERPRET_SCHEMA = """
            {"type": "object", "properties": {
               "intent": {"type": "string"}, "plantName": {"type": "string"}, "spaceName": {"type": "string"},
               "details": {"type": "string"}, "preference": {"type": "string"}},
             "required": ["intent", "plantName", "spaceName", "details", "preference"]}
            """;

    /** The care-note fields a chat message may change, in the order the reply lists them. */
    static final List<String> CARE_FIELDS = List.of("watering", "wateringFrequency", "sunlight", "careLevel",
            "growthRate", "hardinessZones", "family", "description", "indoor", "poisonousToPets", "poisonousToHumans");
    /** Value the model returns for a field the user wants emptied. */
    static final String CLEAR = "CLEAR";

    private static final String CARE_EDIT_INSTRUCTIONS = """
            The user is asking PlantOrganizer's agent to change the stored care notes of one of their plants.
            Return the new value for every field they want changed, and an empty string for every field they
            did not ask to change (never repeat or invent values for those). Fields:
            watering - how much water it wants, one of Frequent, Average, Minimum, None;
            wateringFrequency - how often, as "every N days" (e.g. "every 10 days", "every 7-10 days");
            sunlight - the light it needs, several values separated by commas (e.g. "bright indirect, part shade");
            careLevel - Easy, Medium or Hard;
            growthRate - Low, Medium or High;
            hardinessZones - e.g. "9-11";
            family - the botanical family;
            description - a free-text description of the plant or its care;
            indoor, poisonousToPets, poisonousToHumans - "true" or "false".
            Write CLEAR for a field the user wants emptied. Use the current notes and the recent conversation
            only to resolve references such as "that" or "what you just said".
            """;
    private static final String CARE_EDIT_SCHEMA = """
            {"type": "object", "properties": {
               "watering": {"type": "string"}, "wateringFrequency": {"type": "string"}, "sunlight": {"type": "string"},
               "careLevel": {"type": "string"}, "growthRate": {"type": "string"}, "hardinessZones": {"type": "string"},
               "family": {"type": "string"}, "description": {"type": "string"}, "indoor": {"type": "string"},
               "poisonousToPets": {"type": "string"}, "poisonousToHumans": {"type": "string"}},
             "required": ["watering", "wateringFrequency", "sunlight", "careLevel", "growthRate", "hardinessZones",
                          "family", "description", "indoor", "poisonousToPets", "poisonousToHumans"]}
            """;

    private final LLMClient llm;
    private final JsonNode interpretSchema;
    private final JsonNode careEditSchema;

    public AgentPlanner(LLMClient llm, JsonMapper jsonMapper) {
        this.llm = llm;
        this.interpretSchema = jsonMapper.readTree(INTERPRET_SCHEMA);
        this.careEditSchema = jsonMapper.readTree(CARE_EDIT_SCHEMA);
    }

    /**
     * Works out which care-note fields a chat message changes.
     *
     * @return field name (one of {@link #CARE_FIELDS}) to its new value, {@link #CLEAR} to empty it;
     *         empty when the message names no change or no LLM is configured
     */
    public Map<String, String> extractCareEdits(String message, String plantName, String currentCare, String context) {
        Map<String, String> edits = new java.util.LinkedHashMap<>();
        if (!llm.isConfigured()) {
            return edits;
        }
        JsonNode json = llm.generateStructured(CARE_EDIT_INSTRUCTIONS, context + "\n\nPlant: " + plantName
                + "\nCurrent care notes: " + currentCare + "\n\nLatest message: \"" + message + "\"", careEditSchema);
        for (String field : CARE_FIELDS) {
            String value = JsonValues.text(json.path(field));
            if (value != null) {
                edits.put(field, value);
            }
        }
        return edits;
    }

    // ------------------------------------------------------------------ stage 1: interpret

    /**
     * @param context owned plants, spaces and recent conversation, so the model
     *                can resolve "it" or "the one in the kitchen"
     */
    public ChatIntent interpret(String message, boolean hasPhoto, String context) {
        if (llm.isConfigured()) {
            try {
                String prompt = context + "\n\nLatest message: \"" + message + "\""
                        + (hasPhoto ? "\n(The user attached a photo to this message.)" : "");
                JsonNode json = llm.generateStructured(INTERPRET_INSTRUCTIONS, prompt, interpretSchema);
                return new ChatIntent(intentType(JsonValues.text(json.path("intent")), message, hasPhoto),
                        JsonValues.text(json.path("plantName")), JsonValues.text(json.path("spaceName")),
                        JsonValues.text(json.path("details")), JsonValues.text(json.path("preference")));
            } catch (ExternalServiceException e) {
                log.info("LLM intent detection failed, using keywords: {}", e.getMessage());
            }
        }
        return new ChatIntent(keywordIntent(message, hasPhoto), null, null, message, null);
    }

    private static ChatIntent.Type intentType(String value, String message, boolean hasPhoto) {
        try {
            return ChatIntent.Type.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (RuntimeException e) {
            return keywordIntent(message, hasPhoto);
        }
    }

    /** Fallback when the LLM is unavailable. Package-private for tests. */
    static ChatIntent.Type keywordIntent(String message, boolean hasPhoto) {
        String m = message == null ? "" : message.toLowerCase(Locale.ROOT);
        if (m.contains("undo")) {
            return ChatIntent.Type.UNDO_SCHEDULE;
        }
        if (m.contains("care note")) {
            return ChatIntent.Type.UPDATE_CARE_NOTES;
        }
        if (m.matches("(?s).*\\b(add|save|write|make|jot|put|leave)\\b.*\\bnotes?\\b.*") || m.startsWith("note")) {
            return ChatIntent.Type.ADD_NOTE;
        }
        if (m.matches(".*\\b(i|just) watered\\b.*") || m.startsWith("watered")) {
            return ChatIntent.Type.MARK_WATERED;
        }
        if (m.contains("needs water") || m.contains("need water") || m.contains("what's due") || m.contains("due today")) {
            return ChatIntent.Type.WATERING_STATUS;
        }
        if (m.contains("reminder") || m.contains("notif")) {
            return m.contains("off") || m.contains("stop") || m.contains("disable")
                    ? ChatIntent.Type.NOTIFICATIONS_OFF : ChatIntent.Type.NOTIFICATIONS_ON;
        }
        if (m.contains("free on") || m.contains("i'm free") || m.contains("available") || m.contains("busy")
                || m.contains("travel") || m.contains("my schedule") || m.contains("away from")) {
            return ChatIntent.Type.UPDATE_AVAILABILITY;
        }
        if (m.contains("yellow") || m.contains("brown") || m.contains("dying") || m.contains("droop")
                || m.contains("spots") || m.contains("sick") || m.contains("health") || m.contains("wilting")) {
            return ChatIntent.Type.HEALTH_CHECK;
        }
        if (m.contains("recommend") || m.contains("suggest") || m.contains("looking for")) {
            return ChatIntent.Type.RECOMMEND;
        }
        if (m.contains("wishlist") || m.contains("want to buy")) {
            return ChatIntent.Type.ADD_DESIRED;
        }
        if (m.contains("i bought") || m.contains("i own") || m.contains("i got a") || m.contains("add my")) {
            return ChatIntent.Type.ADD_OWNED;
        }
        if (hasPhoto || m.contains("what plant") || m.contains("identify")) {
            return ChatIntent.Type.IDENTIFY;
        }
        return ChatIntent.Type.GENERAL;
    }

    // ------------------------------------------------------------------ stage 2: plan

    public List<PlanStep> createPlan(AgentRequest request) {
        long userId = request.userId();
        Map<String, Object> p = request.params();
        return switch (request.type()) {
            case IDENTIFY_FROM_PHOTOS -> List.of(
                    PlanStep.of(ToolType.IDENTIFY_PLANT, Map.of(IdentifyPlantTool.ARG_IMAGES, p.get("images"))));
            case IDENTIFY_FROM_TEXT -> List.of(
                    PlanStep.of(ToolType.LOOKUP_PLANT_INFO, Map.of(PlantInfoTool.ARG_DESCRIPTION, p.get("description"))));
            case EXPLAIN_CONFIRMED_PLANT -> List.of(
                    PlanStep.of(ToolType.LOOKUP_PLANT_INFO, Map.of(PlantInfoTool.ARG_QUERY, p.get("query"))),
                    PlanStep.optional(ToolType.SPACE, args(userId, "describe")));
            case ADD_OWNED_PLANT -> List.of(
                    // Missing care data must not block adding the plant: those note fields stay empty (UC02).
                    PlanStep.optional(ToolType.LOOKUP_PLANT_INFO, Map.of(PlantInfoTool.ARG_QUERY, p.get("query"))),
                    PlanStep.then(ToolType.PLANT_COLLECTION, r -> {
                        Map<String, Object> a = args(userId, "addOwned");
                        var query = request.param("query", com.plantorganizer.plantinfo.PlantQuery.class);
                        a.put("scientificName", query.scientificName());
                        putIfPresent(a, "commonName", query.commonName());
                        putIfPresent(a, "lookup", r.get(ToolType.LOOKUP_PLANT_INFO, Object.class));
                        putIfPresent(a, "nickname", p.get("nickname"));
                        putIfPresent(a, "spaceId", p.get("spaceId"));
                        return a;
                    }),
                    PlanStep.then(ToolType.CALENDAR, r -> withPlant(args(userId, "generate"),
                            r.get(ToolType.PLANT_COLLECTION, OwnedPlant.class).getId())));
            case ADD_DESIRED_PLANT -> {
                PlanStep add = PlanStep.then(ToolType.PLANT_COLLECTION, r -> {
                    Map<String, Object> a = merge(args(userId, "addDesired"), p);
                    putIfPresent(a, "lookup", r.get(ToolType.LOOKUP_PLANT_INFO, Object.class));
                    return a;
                });
                String scientificName = (String) p.get("scientificName");
                // Same check as owned plants: a name no source recognises is rejected by the tool.
                yield scientificName == null || scientificName.isBlank() ? List.of(add) : List.of(
                        PlanStep.optional(ToolType.LOOKUP_PLANT_INFO, Map.of(PlantInfoTool.ARG_QUERY,
                                com.plantorganizer.plantinfo.PlantQuery.of(scientificName, (String) p.get("commonName")))),
                        add);
            }
            case GENERATE_SCHEDULE -> List.of(PlanStep.of(ToolType.CALENDAR, merge(args(userId, "generate"), p)));
            case RESEARCH_WATERING -> List.of(
                    PlanStep.of(ToolType.WEB_SEARCH, Map.of(
                            "query", p.get("scientificName"),
                            "question", "How often should " + p.get("plantName") + " (" + p.get("scientificName")
                                    + ") be watered as a house plant, and how do you tell it needs water?",
                            "mode", "watering")),
                    PlanStep.then(ToolType.PLANT_COLLECTION, r -> {
                        ResearchResult research = r.get(ToolType.WEB_SEARCH, ResearchResult.class);
                        if (research.wateringFrequency() == null && research.watering() == null) {
                            return null; // nothing new learned: keep the current data
                        }
                        Map<String, Object> a = withPlant(args(userId, "updateWatering"), (Long) p.get("plantId"));
                        putIfPresent(a, "watering", research.watering());
                        putIfPresent(a, "wateringFrequency", research.wateringFrequency());
                        return a;
                    }),
                    PlanStep.of(ToolType.CALENDAR, merge(args(userId, "generate"), p)));
            case UPDATE_SCHEDULE -> List.of(PlanStep.of(ToolType.CALENDAR, merge(args(userId, "availability"), p)));
            case UNDO_SCHEDULE -> List.of(PlanStep.of(ToolType.CALENDAR, args(userId, "undo")));
            case WATERING_OVERVIEW -> List.of(PlanStep.of(ToolType.CALENDAR, args(userId, "overview")));
            case MARK_WATERED -> List.of(PlanStep.of(ToolType.CALENDAR, merge(args(userId, "water"), p)));
            case SET_NOTIFICATIONS -> List.of(PlanStep.of(ToolType.CALENDAR, merge(args(userId, "notifications"), p)));
            case ANALYZE_HEALTH -> List.of(PlanStep.of(ToolType.ANALYZE_HEALTH, merge(args(userId, null), p)));
            case RESEARCH_HEALTH -> List.of(PlanStep.of(ToolType.WEB_SEARCH, p));
            case RECOMMEND_LEARNED -> List.of(PlanStep.of(ToolType.RECOMMEND, args(userId, "learned")));
            case RECOMMEND_FOR_CONSTRAINTS -> List.of(PlanStep.of(ToolType.RECOMMEND, merge(args(userId, "constraints"), p)));
            case CHECK_DESIRED_FIT -> List.of(
                    PlanStep.optional(ToolType.LOOKUP_PLANT_INFO, Map.of(PlantInfoTool.ARG_QUERY, p.get("query"))));
            case PLACE_PLANT -> List.of(PlanStep.of(ToolType.SPACE, merge(args(userId, "assign"), p)));
            case ANSWER_QUESTION -> p.containsKey("query")
                    ? List.of(PlanStep.optional(ToolType.LOOKUP_PLANT_INFO, Map.of(PlantInfoTool.ARG_QUERY, p.get("query"))),
                              PlanStep.optional(ToolType.SPACE, args(userId, "describe")))
                    : List.of(PlanStep.optional(ToolType.SPACE, args(userId, "describe")));
        };
    }

    private static Map<String, Object> args(long userId, String action) {
        Map<String, Object> a = new HashMap<>();
        a.put("userId", userId);
        if (action != null) {
            a.put("action", action);
        }
        return a;
    }

    private static Map<String, Object> merge(Map<String, Object> base, Map<String, Object> extra) {
        Map<String, Object> a = new HashMap<>(extra);
        a.putAll(base);
        return a;
    }

    private static Map<String, Object> withPlant(Map<String, Object> a, Long plantId) {
        a.put("plantId", plantId);
        return a;
    }

    private static void putIfPresent(Map<String, Object> a, String key, Object value) {
        if (value != null) {
            a.put(key, value);
        }
    }
}
