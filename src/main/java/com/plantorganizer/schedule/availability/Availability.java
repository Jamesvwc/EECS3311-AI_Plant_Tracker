package com.plantorganizer.schedule.availability;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * When the user can water plants (F03): weekly free windows, trips away from
 * home, and the travel/commute buffer they need after getting home.
 *
 * @param weeklyWindows       free times each week; empty means "any time"
 * @param awayPeriods         date ranges the user is away (no watering possible)
 * @param travelBufferMinutes minutes to add after a free window starts (e.g. commute home)
 * @param summary             one-sentence description for the user
 * @param source              TEXT, IMAGE or ICAL
 */
public record Availability(List<TimeWindow> weeklyWindows, List<DateRange> awayPeriods, int travelBufferMinutes,
                           String summary, String source) {

    public record TimeWindow(DayOfWeek day, LocalTime start, LocalTime end) {
    }

    public record DateRange(LocalDate start, LocalDate end, String reason) {

        public boolean contains(LocalDate date) {
            return !date.isBefore(start) && !date.isAfter(end);
        }
    }

    public Availability {
        weeklyWindows = weeklyWindows == null ? List.of() : List.copyOf(weeklyWindows);
        awayPeriods = awayPeriods == null ? List.of() : List.copyOf(awayPeriods);
        travelBufferMinutes = Math.max(0, Math.min(240, travelBufferMinutes));
    }

    public static Availability none() {
        return new Availability(List.of(), List.of(), 0, "No availability given yet: waterings default to 9:00.", null);
    }

    public boolean isEmpty() {
        return weeklyWindows.isEmpty() && awayPeriods.isEmpty() && travelBufferMinutes == 0;
    }

    public boolean isAway(LocalDate date) {
        return awayPeriodContaining(date).isPresent();
    }

    public Optional<DateRange> awayPeriodContaining(LocalDate date) {
        return awayPeriods.stream().filter(p -> p.contains(date)).findFirst();
    }

    public List<TimeWindow> windowsOn(DayOfWeek day) {
        return weeklyWindows.stream().filter(w -> w.day() == day)
                .sorted(Comparator.comparing(TimeWindow::start)).toList();
    }
}
