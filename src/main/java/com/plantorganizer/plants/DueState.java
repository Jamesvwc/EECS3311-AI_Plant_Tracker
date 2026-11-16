package com.plantorganizer.plants;

import java.time.Duration;
import java.time.LocalDateTime;

/** Needs water now. Becomes Overdue once the grace period passes, or Ok if the entry was rescheduled later. */
public final class DueState implements WateringState {

    static final DueState INSTANCE = new DueState();

    private DueState() {
    }

    @Override
    public WateringStatus status() {
        return WateringStatus.DUE;
    }

    @Override
    public void onTick(OwnedPlant plant, LocalDateTime now, LocalDateTime nextDue, Duration overdueAfter) {
        if (nextDue == null || now.isBefore(nextDue)) {
            plant.setState(OkState.INSTANCE);
        } else if (!now.isBefore(nextDue.plus(overdueAfter))) {
            plant.setState(OverdueState.INSTANCE);
        }
    }

    @Override
    public String onWatered(OwnedPlant plant, LocalDateTime when) {
        plant.recordWatering(when);
        plant.setState(OkState.INSTANCE);
        return plant.getDisplayName() + " was watered on time. Nice work!";
    }
}
