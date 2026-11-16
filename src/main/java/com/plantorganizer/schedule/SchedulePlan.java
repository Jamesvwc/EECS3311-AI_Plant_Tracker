package com.plantorganizer.schedule;

import java.time.LocalDateTime;
import java.util.List;

/**
 * A strategy's answer: when to water, at which interval, and whether (and
 * with which plants) the waterings were grouped.
 */
public record SchedulePlan(List<LocalDateTime> times, int intervalDays, boolean grouped, List<String> groupedWith,
                           String reason) {

    public SchedulePlan {
        times = List.copyOf(times);
        groupedWith = List.copyOf(groupedWith);
    }
}
