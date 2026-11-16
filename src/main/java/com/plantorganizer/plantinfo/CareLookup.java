package com.plantorganizer.plantinfo;

import java.util.List;

/**
 * Result of PlantInfoService.lookup(): the profile plus a short log of what
 * happened on the way ("Perenual: not found, retried with common name...").
 * The log is shown in the GUI so the fallback behaviour is visible.
 */
public record CareLookup(PlantCareProfile profile, List<String> notes) {

    public CareLookup {
        notes = notes == null ? List.of() : List.copyOf(notes);
    }
}
