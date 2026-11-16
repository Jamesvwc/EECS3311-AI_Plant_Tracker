package com.plantorganizer.health;

import java.util.List;

import com.plantorganizer.plants.HealthRecord;

/**
 * F05 result shown to the user: the stored health record (assessment, care
 * steps, trend versus the previous check) plus any warnings.
 *
 * @param speciesWarning set when the photo looks like a different species than the stored plant
 */
public record HealthReport(long plantId, String plantName, HealthRecord record, Long photoId, String speciesWarning,
                           List<String> notes) {

    public HealthReport {
        notes = notes == null ? List.of() : List.copyOf(notes);
    }
}
