package com.plantorganizer.memory;

import java.util.List;

/** What the agent believes the user likes (Stage 1 UserPreferenceModel), as handed to recommendation prompts. */
public record UserPreferenceModel(List<String> likes, List<String> dislikes, List<String> notes) {

    public UserPreferenceModel {
        likes = List.copyOf(likes);
        dislikes = List.copyOf(dislikes);
        notes = List.copyOf(notes);
    }

    public boolean isEmpty() {
        return likes.isEmpty() && dislikes.isEmpty() && notes.isEmpty();
    }

    /** True when the plant name matches something the user rejected. */
    public boolean dislikes(String name) {
        if (name == null) {
            return false;
        }
        String n = name.toLowerCase();
        return dislikes.stream().map(String::toLowerCase).anyMatch(d -> d.contains(n) || n.contains(d));
    }
}
