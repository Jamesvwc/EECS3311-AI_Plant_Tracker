package com.plantorganizer.agent;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.plantorganizer.identification.PlantImage;
import com.plantorganizer.plantinfo.PlantQuery;
import com.plantorganizer.schedule.availability.AvailabilityInput;

/**
 * A unit of work handed to PlantAgent.run(): what to do, for which user, with
 * which inputs. Build one with the static factory methods so each request
 * type carries exactly the parameters AgentPlanner expects.
 */
public record AgentRequest(Type type, long userId, Map<String, Object> params) {

    public enum Type {
        IDENTIFY_FROM_PHOTOS, IDENTIFY_FROM_TEXT, EXPLAIN_CONFIRMED_PLANT,
        ADD_OWNED_PLANT, ADD_DESIRED_PLANT,
        GENERATE_SCHEDULE, RESEARCH_WATERING, UPDATE_SCHEDULE, UNDO_SCHEDULE,
        WATERING_OVERVIEW, MARK_WATERED, SET_NOTIFICATIONS,
        ANALYZE_HEALTH, RESEARCH_HEALTH,
        RECOMMEND_LEARNED, RECOMMEND_FOR_CONSTRAINTS,
        CHECK_DESIRED_FIT, PLACE_PLANT, ANSWER_QUESTION
    }

    public AgentRequest {
        Map<String, Object> copy = new HashMap<>();
        params.forEach((k, v) -> {
            if (v != null) {
                copy.put(k, v);
            }
        });
        params = Map.copyOf(copy);
    }

    public <T> T param(String key, Class<T> type) {
        Object value = params.get(key);
        return value == null ? null : type.cast(value);
    }

    private static AgentRequest of(Type type, long userId, Object... keyValues) {
        Map<String, Object> map = new HashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            map.put((String) keyValues[i], keyValues[i + 1]);
        }
        return new AgentRequest(type, userId, map);
    }

    public static AgentRequest identifyFromPhotos(long userId, List<PlantImage> photos) {
        return of(Type.IDENTIFY_FROM_PHOTOS, userId, "images", photos);
    }

    public static AgentRequest identifyFromText(long userId, String description) {
        return of(Type.IDENTIFY_FROM_TEXT, userId, "description", description);
    }

    public static AgentRequest explainConfirmedPlant(long userId, PlantQuery query) {
        return of(Type.EXPLAIN_CONFIRMED_PLANT, userId, "query", query);
    }

    public static AgentRequest addOwnedPlant(long userId, PlantQuery query, String nickname, Long spaceId) {
        return of(Type.ADD_OWNED_PLANT, userId, "query", query, "nickname", nickname, "spaceId", spaceId);
    }

    public static AgentRequest addDesiredPlant(long userId, String scientificName, String commonName, Long spaceId,
                                               String note, String imageUrl) {
        return of(Type.ADD_DESIRED_PLANT, userId, "scientificName", scientificName, "commonName", commonName,
                "spaceId", spaceId, "note", note, "imageUrl", imageUrl);
    }

    public static AgentRequest generateSchedule(long userId, long plantId) {
        return of(Type.GENERATE_SCHEDULE, userId, "plantId", plantId);
    }

    public static AgentRequest researchWatering(long userId, long plantId, String scientificName, String name) {
        return of(Type.RESEARCH_WATERING, userId, "plantId", plantId, "scientificName", scientificName, "plantName", name);
    }

    public static AgentRequest updateSchedule(long userId, AvailabilityInput input) {
        return of(Type.UPDATE_SCHEDULE, userId, "input", input);
    }

    public static AgentRequest undoSchedule(long userId) {
        return of(Type.UNDO_SCHEDULE, userId);
    }

    public static AgentRequest wateringOverview(long userId) {
        return of(Type.WATERING_OVERVIEW, userId);
    }

    public static AgentRequest markWatered(long userId, long plantId) {
        return of(Type.MARK_WATERED, userId, "plantId", plantId);
    }

    public static AgentRequest setNotifications(long userId, long plantId, boolean enabled) {
        return of(Type.SET_NOTIFICATIONS, userId, "plantId", plantId, "enabled", enabled);
    }

    public static AgentRequest analyzeHealth(long userId, long plantId, PlantImage photo, String description,
                                             boolean confirmSpecies) {
        return of(Type.ANALYZE_HEALTH, userId, "plantId", plantId, "photo", photo, "description", description,
                "confirmSpecies", confirmSpecies);
    }

    /** "Not satisfied" with a health check: search the web and dig deeper. */
    public static AgentRequest researchHealth(long userId, String searchQuery, String question, String context) {
        return of(Type.RESEARCH_HEALTH, userId, "query", searchQuery, "question", question, "context", context);
    }

    public static AgentRequest recommendLearned(long userId) {
        return of(Type.RECOMMEND_LEARNED, userId);
    }

    public static AgentRequest recommendFor(long userId, String constraints) {
        return of(Type.RECOMMEND_FOR_CONSTRAINTS, userId, "constraints", constraints);
    }

    /** F09: does the wishlist plant suit the space the user picked? */
    public static AgentRequest checkDesiredFit(long userId, PlantQuery query, String spacePath, String light,
                                               List<String> plantsAlreadyThere) {
        return of(Type.CHECK_DESIRED_FIT, userId, "query", query, "spacePath", spacePath, "light", light,
                "neighbours", plantsAlreadyThere);
    }

    public static AgentRequest placePlant(long userId, long plantId, long spaceId) {
        return of(Type.PLACE_PLANT, userId, "plantId", plantId, "spaceId", spaceId);
    }

    /** CLI chat: a care or general question, optionally about a known species. */
    public static AgentRequest answerQuestion(long userId, String question, PlantQuery aboutPlant, String context) {
        return of(Type.ANSWER_QUESTION, userId, "question", question, "query", aboutPlant, "context", context);
    }
}
