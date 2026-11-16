package com.plantorganizer.agent;

/**
 * What the planner understood from a chat message: the intent plus the
 * arguments it extracted. Any argument may be null.
 *
 * @param details    the part of the message the tool needs (a description,
 *                   constraints, availability text or a question)
 * @param preference a lasting preference the user revealed ("I have a cat"), saved to AgentMemory
 */
public record ChatIntent(Type type, String plantName, String spaceName, String details, String preference) {

    public enum Type {
        IDENTIFY, CARE_QUESTION, ADD_OWNED, ADD_DESIRED, HEALTH_CHECK, UPDATE_AVAILABILITY, UNDO_SCHEDULE,
        RECOMMEND, REFRESH_RECOMMENDATIONS, PLACE_PLANT, WATERING_STATUS, MARK_WATERED, NOTIFICATIONS_ON,
        NOTIFICATIONS_OFF, CHECK_FIT, UPDATE_CARE_NOTES, ADD_NOTE, GENERAL
    }
}
