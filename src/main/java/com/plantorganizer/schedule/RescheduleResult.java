package com.plantorganizer.schedule;

import java.util.List;

import com.plantorganizer.schedule.availability.Availability;

/**
 * F03 result: what availability was understood and how the calendar changed.
 *
 * @param warnings one line per plant whose watering needs the availability
 *                 can't fully meet (waterings end up much further apart than
 *                 the plant's interval); empty when every plant is covered
 */
public record RescheduleResult(
        Availability availability,
        int plantsRescheduled,
        int entriesBefore,
        int entriesAfter,
        List<String> examples,
        List<String> warnings,
        String explanation) {

    public RescheduleResult {
        examples = examples == null ? List.of() : List.copyOf(examples);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }

    public RescheduleResult withExplanation(String text) {
        return new RescheduleResult(availability, plantsRescheduled, entriesBefore, entriesAfter, examples, warnings, text);
    }
}
