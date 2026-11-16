package com.plantorganizer.plants;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;

/** F04: the State pattern on OwnedPlant, and F05's HealthRecord comparison. */
class WateringStateTest {

    private static final LocalDateTime DUE = LocalDateTime.of(2026, 11, 13, 9, 0);
    private static final Duration GRACE = Duration.ofHours(24);

    private static OwnedPlant plant() {
        return new OwnedPlant(1, "Monstera deliciosa", "Swiss cheese plant", null, new CareNotes(), DUE.minusDays(7));
    }

    @Test
    void okBecomesDueThenOverdueAsTimePasses() {
        OwnedPlant p = plant();
        assertThat(p.checkDue(DUE.minusHours(1), DUE, GRACE)).isEqualTo(WateringStatus.OK);
        assertThat(p.checkDue(DUE.plusHours(1), DUE, GRACE)).isEqualTo(WateringStatus.DUE);
        assertThat(p.checkDue(DUE.plusHours(30), DUE, GRACE)).isEqualTo(WateringStatus.OVERDUE);
    }

    @Test
    void wateringReturnsToOkAndTheMessageDependsOnTheState() {
        OwnedPlant late = plant();
        late.checkDue(DUE.plusDays(2), DUE, GRACE);
        assertThat(late.markWatered(DUE.plusDays(2))).contains("late");
        assertThat(late.getWateringStatus()).isEqualTo(WateringStatus.OK);
        assertThat(late.getLastWateredAt()).isEqualTo(DUE.plusDays(2));

        OwnedPlant onTime = plant();
        onTime.checkDue(DUE.plusHours(2), DUE, GRACE);
        assertThat(onTime.markWatered(DUE.plusHours(2))).contains("on time");

        assertThat(plant().markWatered(DUE.minusDays(3))).contains("ahead of schedule");
    }

    @Test
    void rescheduledIntoTheFutureMeansNotDueAnymore() {
        OwnedPlant p = plant();
        p.checkDue(DUE.plusDays(3), DUE, GRACE);
        assertThat(p.getWateringStatus()).isEqualTo(WateringStatus.OVERDUE);
        assertThat(p.checkDue(DUE.plusDays(3), DUE.plusDays(4), GRACE)).isEqualTo(WateringStatus.OK);
    }

    @Test
    void healthRecordsCompareToTheEarlierCheck() {
        HealthRecord before = new HealthRecord(1, null, DUE, null, 4, HealthStatus.NEEDS_ATTENTION, "", "", "", "", null);
        HealthRecord better = new HealthRecord(1, null, DUE.plusDays(7), null, 7, HealthStatus.MINOR_ISSUES, "", "", "", "", null);
        HealthRecord same = new HealthRecord(1, null, DUE.plusDays(7), null, 5, HealthStatus.MINOR_ISSUES, "", "", "", "", null);
        assertThat(better.compareTo(before)).isEqualTo(HealthRecord.Trend.IMPROVING);
        assertThat(before.compareTo(better)).isEqualTo(HealthRecord.Trend.WORSENING);
        assertThat(same.compareTo(before)).isEqualTo(HealthRecord.Trend.STABLE);
        assertThat(same.compareTo(null)).isEqualTo(HealthRecord.Trend.FIRST_CHECK);
    }
}
