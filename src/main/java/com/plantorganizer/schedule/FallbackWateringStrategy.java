package com.plantorganizer.schedule;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Component;

/**
 * Concrete strategy: a best-effort schedule that ignores other plants. Waters
 * every N days from the earliest start, fitted to the user's availability.
 * It always succeeds, which is why ScheduleManager falls back to it.
 */
@Component
public class FallbackWateringStrategy implements SchedulingStrategy {

    @Override
    public String name() {
        return "Fallback (best effort)";
    }

    @Override
    public Optional<SchedulePlan> plan(SchedulingContext ctx) {
        int interval = ctx.interval().days();
        List<LocalDateTime> times = new ArrayList<>();
        LocalDateTime last = null;
        for (LocalDate date = ctx.earliestStart(); !date.isAfter(ctx.horizonEnd()); date = date.plusDays(interval)) {
            LocalDateTime time = AvailabilityFitter.fit(date, ctx.availability(), ctx.earliestStart());
            if (last == null || time.isAfter(last.plusHours(12))) {
                times.add(time);
                last = time;
            }
        }
        return Optional.of(new SchedulePlan(times, interval, false, List.of(),
                "watering every " + interval + " days on its own schedule"));
    }
}
