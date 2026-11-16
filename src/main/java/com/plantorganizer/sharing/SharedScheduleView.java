package com.plantorganizer.sharing;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

/**
 * F12: what a guest with a share link sees: plant names, watering needs and
 * schedule, and sunlight needs only (no health records, notes or locations).
 */
public record SharedScheduleView(String ownerName, String label, Instant expiresAt, List<SharedPlant> plants) {

    public record SharedPlant(String name, String scientificName, String watering, String wateringFrequency,
                              Integer intervalDays, List<String> sunlight, List<LocalDateTime> nextWaterings) {
    }
}
