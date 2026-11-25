package com.plantorganizer.schedule;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;

/** F03: rescheduling reports plants whose watering needs the user's availability can't meet. */
class UnmetWateringNeedTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 23, 12, 0);

    private static SchedulePlan plan(int intervalDays, int... daysFromNow) {
        List<LocalDateTime> times = java.util.Arrays.stream(daysFromNow).mapToObj(NOW::plusDays).toList();
        return new SchedulePlan(times, intervalDays, false, List.of(), "test");
    }

    @Test
    void gapsCloseToTheIntervalAreFine() {
        assertThat(ScheduleManager.unmetNeed("Fern", plan(7, 5, 12, 19, 26), NOW)).isEmpty();
        // shifted a few days by availability, still within half an interval (at least 2 days)
        assertThat(ScheduleManager.unmetNeed("Fern", plan(3, 3, 8, 11), NOW)).isEmpty();
    }

    @Test
    void gapsMuchLongerThanTheIntervalAreReported() {
        assertThat(ScheduleManager.unmetNeed("Fern", plan(3, 3, 13, 16), NOW))
                .hasValueSatisfying(w -> assertThat(w).contains("Fern needs water every 3 days", "gaps of up to 10 days"));
        assertThat(ScheduleManager.unmetNeed("Cactus", plan(14, 12, 33), NOW)).isEmpty();
    }
}
