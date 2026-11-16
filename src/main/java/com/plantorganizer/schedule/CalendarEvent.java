package com.plantorganizer.schedule;

import java.util.Set;

/** What changed in the calendar; passed to every {@link CalendarObserver}. */
public record CalendarEvent(Type type, long ownerId, Set<Long> plantIds) {

    public enum Type { ENTRIES_ADDED, RESCHEDULED, COMPLETED, UNDONE, REMOVED }

    public CalendarEvent {
        plantIds = Set.copyOf(plantIds);
    }
}
