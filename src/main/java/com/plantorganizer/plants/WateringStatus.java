package com.plantorganizer.plants;

/** The stored name of each watering state; {@link #state()} returns the State object that holds its behaviour. */
public enum WateringStatus {
    OK, DUE, OVERDUE;

    public WateringState state() {
        return switch (this) {
            case OK -> OkState.INSTANCE;
            case DUE -> DueState.INSTANCE;
            case OVERDUE -> OverdueState.INSTANCE;
        };
    }
}
