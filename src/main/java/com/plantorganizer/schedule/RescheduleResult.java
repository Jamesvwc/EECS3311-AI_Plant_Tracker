package com.plantorganizer.schedule;

import java.util.List;

import com.plantorganizer.schedule.availability.Availability;

/** F03 result: what availability was understood and how the calendar changed. */
public record RescheduleResult(
        Availability availability,
        int plantsRescheduled,
        int entriesBefore,
        int entriesAfter,
        List<String> examples,
        String explanation) {

    public RescheduleResult withExplanation(String text) {
        return new RescheduleResult(availability, plantsRescheduled, entriesBefore, entriesAfter, examples, text);
    }
}
