package com.plantorganizer.schedule;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.plantorganizer.plants.IntervalQuality;
import com.plantorganizer.schedule.availability.Availability;

/** F02/F03: the two scheduling strategies and fitting waterings around the user's availability. */
class SchedulingStrategyTest {

    private static final LocalDate START = LocalDate.of(2026, 11, 13); // a Friday
    private final GroupedWateringStrategy grouped = new GroupedWateringStrategy();
    private final FallbackWateringStrategy fallback = new FallbackWateringStrategy();

    private static List<LocalDateTime> every(int days, LocalDate from, int count) {
        List<LocalDateTime> times = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            times.add(from.plusDays((long) i * days).atTime(9, 0));
        }
        return times;
    }

    private static SchedulingContext context(int days, IntervalQuality quality, Map<String, List<LocalDateTime>> others,
                                             Map<String, Integer> intervals, Availability availability) {
        return new SchedulingContext("New plant", new WateringInterval(days, quality, "test"), START,
                START.plusDays(56), others, intervals, availability);
    }

    @Test
    void groupsWithAPlantWhoseIntervalIsWithinADay() {
        var ctx = context(7, IntervalQuality.GOOD, Map.of("Monstera", every(8, START.plusDays(2), 7)),
                Map.of("Monstera", 8), Availability.none());

        SchedulePlan plan = grouped.plan(ctx).orElseThrow();

        assertThat(plan.grouped()).isTrue();
        assertThat(plan.intervalDays()).isEqualTo(8);
        assertThat(plan.groupedWith()).containsExactly("Monstera");
        assertThat(plan.times()).startsWith(START.plusDays(2).atTime(9, 0), START.plusDays(10).atTime(9, 0));
    }

    @Test
    void groupsWhenOneIntervalIsAMultipleOfTheOther() {
        var ctx = context(14, IntervalQuality.GOOD, Map.of("Pothos", every(7, START, 9)), Map.of("Pothos", 7),
                Availability.none());

        SchedulePlan plan = grouped.plan(ctx).orElseThrow();

        assertThat(plan.intervalDays()).isEqualTo(14);
        assertThat(every(7, START, 9)).containsAll(plan.times());
    }

    @Test
    void groupingIsImpossibleForIncompatibleIntervalsOrMissingData() {
        Map<String, List<LocalDateTime>> others = Map.of("Cactus", every(17, START, 4));
        assertThat(grouped.plan(context(8, IntervalQuality.GOOD, others, Map.of("Cactus", 17), Availability.none()))).isEmpty();
        assertThat(grouped.plan(context(17, IntervalQuality.MISSING, others, Map.of("Cactus", 17), Availability.none()))).isEmpty();
        assertThat(grouped.plan(context(8, IntervalQuality.GOOD, Map.of(), Map.of(), Availability.none()))).isEmpty();
    }

    @Test
    void fallbackAlwaysPlansAndUsesTheFirstFreeWindowPlusTravelTime() {
        Availability evenings = new Availability(
                List.of(new Availability.TimeWindow(DayOfWeek.FRIDAY, LocalTime.of(17, 0), LocalTime.of(21, 0)),
                        new Availability.TimeWindow(DayOfWeek.SATURDAY, LocalTime.of(10, 0), LocalTime.of(18, 0))),
                List.of(), 30, "Fri evening, Sat", "TEXT");

        SchedulePlan plan = fallback.plan(context(7, IntervalQuality.MISSING, Map.of(), Map.of(), evenings)).orElseThrow();

        assertThat(plan.grouped()).isFalse();
        assertThat(plan.times()).isNotEmpty().allMatch(t -> t.getDayOfWeek() == DayOfWeek.FRIDAY
                ? t.toLocalTime().equals(LocalTime.of(17, 30)) : t.toLocalTime().equals(LocalTime.of(10, 30)));
    }

    /** Regression: a short interval during a long trip used to loop forever in the grouped strategy. */
    @Test
    void watersBeforeATripAndSoonAfterComingBack() {
        var trip = new Availability.DateRange(START.plusDays(5), START.plusDays(14), "Holiday");
        Availability away = new Availability(List.of(), List.of(trip), 0, "away", "TEXT");
        var ctx = context(4, IntervalQuality.GOOD, Map.of("Fern", every(4, START, 15)), Map.of("Fern", 4), away);

        SchedulePlan plan = grouped.plan(ctx).orElseThrow();

        assertThat(plan.times()).noneMatch(t -> trip.contains(t.toLocalDate()));
        assertThat(plan.times()).anyMatch(t -> t.toLocalDate().equals(trip.start().minusDays(1)));
        // back home: watered within two days (it may line up with the fern's next watering)
        assertThat(plan.times()).anyMatch(t -> !t.toLocalDate().isBefore(trip.end().plusDays(1))
                && !t.toLocalDate().isAfter(trip.end().plusDays(2)));
    }

    @Test
    void readsTheWateringIntervalFromCareData() {
        var care = new com.plantorganizer.plants.CareNotes();
        care.setWateringFrequency("every 7-10 days");
        assertThat(WateringInterval.from(care)).extracting(WateringInterval::days, WateringInterval::quality)
                .containsExactly(8, IntervalQuality.GOOD);

        care.setWateringFrequency(null);
        care.setWatering("Minimum");
        assertThat(WateringInterval.from(care).quality()).isEqualTo(IntervalQuality.ESTIMATED);

        care.setWatering(null);
        assertThat(WateringInterval.from(care)).extracting(WateringInterval::days, WateringInterval::quality)
                .containsExactly(7, IntervalQuality.MISSING);
    }
}
