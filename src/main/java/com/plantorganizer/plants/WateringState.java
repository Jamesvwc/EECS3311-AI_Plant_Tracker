package com.plantorganizer.plants;

import java.time.Duration;
import java.time.LocalDateTime;

/**
 * State pattern (F04). An OwnedPlant (the context) delegates "time passed"
 * and "the user watered it" to its current state object, and each state
 * decides the next state itself. This replaces a growing if/else on a status
 * field in the notification code.
 */
public interface WateringState {

    WateringStatus status();

    /**
     * Called by the reminder timer.
     *
     * @param nextDue      the plant's earliest unfinished watering, or null if none is planned
     * @param overdueAfter how long after the due time the plant counts as overdue
     */
    void onTick(OwnedPlant plant, LocalDateTime now, LocalDateTime nextDue, Duration overdueAfter);

    /** Records a watering and returns a short message describing it (early / on time / late). */
    String onWatered(OwnedPlant plant, LocalDateTime when);
}
