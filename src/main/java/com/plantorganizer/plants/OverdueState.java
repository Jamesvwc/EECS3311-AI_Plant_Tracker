package com.plantorganizer.plants;

import java.time.Duration;
import java.time.LocalDateTime;

/** A watering was missed. Stays Overdue until the user waters it (or the missed entry is rescheduled). */
public final class OverdueState implements WateringState {

    static final OverdueState INSTANCE = new OverdueState();

    private OverdueState() {
    }

    @Override
    public WateringStatus status() {
        return WateringStatus.OVERDUE;
    }

    @Override
    public void onTick(OwnedPlant plant, LocalDateTime now, LocalDateTime nextDue, Duration overdueAfter) {
        if (nextDue == null || now.isBefore(nextDue)) {
            plant.setState(OkState.INSTANCE);
        }
    }

    @Override
    public String onWatered(OwnedPlant plant, LocalDateTime when) {
        plant.recordWatering(when);
        plant.setState(OkState.INSTANCE);
        return plant.getDisplayName() + " was watered late. Check whether the leaves are drooping or the soil "
                + "pulled away from the pot; a slow, thorough soak helps dry soil take water in again.";
    }
}
