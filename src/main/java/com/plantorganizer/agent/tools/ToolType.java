package com.plantorganizer.agent.tools;

/** The tools the agent can use; ToolFactory creates one concrete Tool per value. */
public enum ToolType {
    /** F01: resize photos and identify the plant with Pl@ntNet. */
    IDENTIFY_PLANT,
    /** F01/UC13: care lookup (Perenual, retry, AI fallback) and identification from a text description. */
    LOOKUP_PLANT_INFO,
    /** F08/F09: add plants to the owned list or the wishlist. */
    PLANT_COLLECTION,
    /** F02-F04: generate/update/undo the watering schedule, mark watered, reminders. */
    CALENDAR,
    /** F05: assess a plant's health from a photo and/or description. */
    ANALYZE_HEALTH,
    /** F02/F05/F09: retrieve web pages and write an answer from them. */
    WEB_SEARCH,
    /** F06/F07: recommendations. */
    RECOMMEND,
    /** F10: place plants in spaces and describe the user's spaces. */
    SPACE
}
