package com.plantorganizer.agent;

import java.util.List;

import com.plantorganizer.plantinfo.PlantCareProfile;

/**
 * The final F01 result shown to the user: the care profile, the agent's
 * explanation of how to care for the plant and where to place it, and notes
 * describing which sources were used.
 *
 * @param aiExplanation true when Cohere wrote the explanation; false when the
 *                      agent fell back to a summary built from the profile
 */
public record PlantReport(PlantCareProfile profile, String explanation, boolean aiExplanation, List<String> notes) {

    public PlantReport {
        notes = notes == null ? List.of() : List.copyOf(notes);
    }
}
