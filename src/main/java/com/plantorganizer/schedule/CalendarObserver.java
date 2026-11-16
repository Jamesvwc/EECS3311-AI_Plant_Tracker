package com.plantorganizer.schedule;

/**
 * Observer pattern, observer role (F04). ScheduleManager (the subject) calls
 * {@link #update} whenever calendar entries are added, rescheduled, completed
 * or undone, so dependants such as NotificationService stay in sync without
 * the calendar knowing anything about them.
 */
public interface CalendarObserver {

    void update(CalendarEvent event);
}
