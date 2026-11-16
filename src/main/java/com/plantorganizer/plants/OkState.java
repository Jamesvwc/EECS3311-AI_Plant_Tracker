package com.plantorganizer.plants;

import java.time.Duration;
import java.time.LocalDateTime;

/** Watered and not due yet. Becomes Due at the planned time, or Overdue if the timer missed the due window. */
public final class OkState implements WateringState {

    static final OkState INSTANCE = new OkState();

    private OkState() {
    }

    @Override
    public WateringStatus status() {
        return WateringStatus.OK;
    }

    @Override
    public void onTick(OwnedPlant plant, LocalDateTime now, LocalDateTime nextDue, Duration overdueAfter) {
        if (nextDue == null || now.isBefore(nextDue)) {
            return;
        }
        plant.setState(now.isBefore(nextDue.plus(overdueAfter)) ? DueState.INSTANCE : OverdueState.INSTANCE);
    }

    @Override
    public String onWatered(OwnedPlant plant, LocalDateTime when) {
        plant.recordWatering(when);
        return plant.getDisplayName() + " was watered ahead of schedule. Check the soil first next time; "
                + "most plants prefer to dry out a little between waterings.";
    }
}
