package com.plantorganizer.schedule;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;

import org.springframework.stereotype.Component;

import com.plantorganizer.plants.IntervalQuality;

/**
 * Concrete strategy: lines the new plant's waterings up with plants already in
 * the calendar so the user waters several plants in one go (F02 "group
 * watering times together ... to minimize the complexity of the calendar").
 *
 * Two intervals are compatible when they are within a day or two of each other
 * (then the new plant adopts the other plant's interval) or one is a whole
 * multiple of the other (every 2nd or 3rd watering coincides). Returns empty
 * when no plant is compatible or the interval is only a default guess, which
 * makes ScheduleManager switch to FallbackWateringStrategy.
 */
@Component
public class GroupedWateringStrategy implements SchedulingStrategy {

    @Override
    public String name() {
        return "Grouped with other plants";
    }

    @Override
    public Optional<SchedulePlan> plan(SchedulingContext ctx) {
        int wanted = ctx.interval().days();
        if (ctx.interval().quality() == IntervalQuality.MISSING || ctx.otherPlants().isEmpty()) {
            return Optional.empty();
        }
        int tolerance = wanted <= 10 ? 1 : 2;

        String anchorName = null;
        int chosenInterval = 0;
        LocalDateTime anchorStart = null;
        int bestAdjustment = Integer.MAX_VALUE;
        for (Map.Entry<String, Integer> other : ctx.otherIntervals().entrySet()) {
            int theirs = other.getValue();
            int candidate;
            if (Math.abs(theirs - wanted) <= tolerance) {
                candidate = theirs;
            } else if (wanted % theirs == 0 || theirs % wanted == 0) {
                candidate = wanted;
            } else {
                continue;
            }
            Optional<LocalDateTime> start = ctx.otherPlants().getOrDefault(other.getKey(), List.of()).stream()
                    .filter(t -> !t.toLocalDate().isBefore(ctx.earliestStart()))
                    .filter(t -> !t.toLocalDate().isAfter(ctx.earliestStart().plusDays(wanted + tolerance)))
                    .findFirst();
            int adjustment = Math.abs(candidate - wanted);
            if (start.isPresent() && adjustment < bestAdjustment) {
                anchorName = other.getKey();
                chosenInterval = candidate;
                anchorStart = start.get();
                bestAdjustment = adjustment;
            }
        }
        if (anchorName == null) {
            return Optional.empty();
        }

        // All waterings already in the calendar, to snap onto (within the tolerance).
        TreeSet<LocalDateTime> existing = new TreeSet<>();
        ctx.otherPlants().values().forEach(existing::addAll);

        List<LocalDateTime> times = new ArrayList<>();
        TreeSet<String> partners = new TreeSet<>();
        LocalDateTime cursor = anchorStart;
        while (!cursor.toLocalDate().isAfter(ctx.horizonEnd())) {
            LocalDateTime snapped = nearest(existing, cursor, tolerance);
            if (snapped != null && ctx.availability() != null && ctx.availability().isAway(snapped.toLocalDate())) {
                snapped = null; // never line up with another plant's watering on a day the user is away
            }
            LocalDateTime time = snapped != null ? snapped
                    : AvailabilityFitter.fit(cursor.toLocalDate(), ctx.availability(), ctx.earliestStart());
            if (snapped != null) {
                partners.addAll(plantsAt(ctx.otherPlants(), snapped));
            }
            if (times.isEmpty() || time.isAfter(times.get(times.size() - 1).plusHours(12))) {
                times.add(time);
            }
            // Always move forward. If the watering was pulled earlier (to before a trip), try again the
            // next day so the first free day after the trip gets a watering too.
            LocalDateTime next = time.plusDays(chosenInterval);
            cursor = next.isAfter(cursor) ? next : cursor.plusDays(1);
        }
        String reason = chosenInterval == wanted
                ? "watering every " + wanted + " days, on the same days as " + String.join(", ", partners)
                : "watering every " + chosenInterval + " days instead of " + wanted
                  + " so it can be watered together with " + String.join(", ", partners);
        return Optional.of(new SchedulePlan(times, chosenInterval, true, List.copyOf(partners), reason));
    }

    private static LocalDateTime nearest(TreeSet<LocalDateTime> times, LocalDateTime target, int toleranceDays) {
        LocalDateTime best = null;
        long bestDistance = Long.MAX_VALUE;
        for (LocalDateTime t : times.subSet(target.minusDays(toleranceDays), true, target.plusDays(toleranceDays), true)) {
            long distance = Math.abs(java.time.Duration.between(t, target).toMinutes());
            if (distance < bestDistance) {
                best = t;
                bestDistance = distance;
            }
        }
        return best;
    }

    private static List<String> plantsAt(Map<String, List<LocalDateTime>> plants, LocalDateTime time) {
        LocalDate day = time.toLocalDate();
        return plants.entrySet().stream()
                .filter(e -> e.getValue().stream().anyMatch(t -> t.toLocalDate().equals(day)))
                .map(Map.Entry::getKey)
                .toList();
    }
}
