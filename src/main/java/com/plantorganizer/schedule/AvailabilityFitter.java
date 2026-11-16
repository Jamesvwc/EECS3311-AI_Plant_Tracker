package com.plantorganizer.schedule;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Optional;

import com.plantorganizer.schedule.availability.Availability;
import com.plantorganizer.schedule.availability.Availability.TimeWindow;

/**
 * Moves a planned watering onto a time the user is actually home (F03):
 * the first free window of the day plus their travel buffer, shifting a day or
 * two earlier/later when that day has no free time, and watering before a trip
 * instead of during it. Shared by both scheduling strategies.
 */
final class AvailabilityFitter {

    static final LocalTime DEFAULT_TIME = LocalTime.of(9, 0);
    private static final int MAX_SHIFT_DAYS = 3;

    private AvailabilityFitter() {
    }

    static LocalDateTime fit(LocalDate plannedDate, Availability availability, LocalDate notBefore) {
        LocalDate date = plannedDate;
        if (availability == null || availability.isEmpty()) {
            return date.atTime(DEFAULT_TIME);
        }
        // During a trip: water the last free day before leaving, otherwise the first day back.
        Optional<Availability.DateRange> trip = availability.awayPeriodContaining(date);
        if (trip.isPresent()) {
            for (LocalDate d = trip.get().start().minusDays(1); !d.isBefore(notBefore)
                    && !d.isBefore(trip.get().start().minusDays(MAX_SHIFT_DAYS)); d = d.minusDays(1)) {
                Optional<LocalTime> time = freeTime(d, availability);
                if (time.isPresent()) {
                    return d.atTime(time.get());
                }
            }
            date = trip.get().end().plusDays(1);
        }
        for (int shift = 0; shift <= MAX_SHIFT_DAYS; shift++) {
            for (int sign : new int[] {-1, 1}) {
                if (shift == 0 && sign == 1) {
                    continue;
                }
                LocalDate candidate = date.plusDays((long) sign * shift);
                if (candidate.isBefore(notBefore)) {
                    continue;
                }
                Optional<LocalTime> time = freeTime(candidate, availability);
                if (time.isPresent()) {
                    return candidate.atTime(time.get());
                }
            }
        }
        // No free day nearby (e.g. a long trip): water the day before leaving, or the day after returning.
        return availability.awayPeriodContaining(date)
                .map(p -> p.start().minusDays(1).isBefore(notBefore) ? p.end().plusDays(1) : p.start().minusDays(1))
                .map(d -> d.atTime(freeTime(d, availability).orElse(DEFAULT_TIME)))
                .orElse(date.atTime(DEFAULT_TIME));
    }

    private static Optional<LocalTime> freeTime(LocalDate date, Availability availability) {
        if (availability.isAway(date)) {
            return Optional.empty();
        }
        if (availability.weeklyWindows().isEmpty()) {
            return Optional.of(DEFAULT_TIME);
        }
        return availability.windowsOn(date.getDayOfWeek()).stream()
                .map(w -> startWithBuffer(w, availability.travelBufferMinutes()))
                .filter(Optional::isPresent)
                .map(Optional::get)
                .findFirst();
    }

    private static Optional<LocalTime> startWithBuffer(TimeWindow window, int bufferMinutes) {
        LocalTime start = window.start().plusMinutes(bufferMinutes);
        boolean wrappedPastMidnight = start.isBefore(window.start());
        return !wrappedPastMidnight && start.isBefore(window.end()) ? Optional.of(start) : Optional.empty();
    }
}
