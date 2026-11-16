package com.plantorganizer.schedule;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import com.plantorganizer.schedule.availability.Availability;

/**
 * Everything a {@link SchedulingStrategy} needs: the plant being scheduled,
 * its interval, the user's availability and the other plants' planned
 * waterings (to group with).
 *
 * @param otherPlants other plants' planned watering times, by plant name
 * @param otherIntervals other plants' intervals in days, by plant name
 */
public record SchedulingContext(
        String plantName,
        WateringInterval interval,
        LocalDate earliestStart,
        LocalDate horizonEnd,
        Map<String, List<LocalDateTime>> otherPlants,
        Map<String, Integer> otherIntervals,
        Availability availability) {
}
