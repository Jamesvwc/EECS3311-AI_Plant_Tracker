package com.plantorganizer.schedule.command;

/**
 * Command pattern (F02/F03): one reversible change to the calendar. Each
 * command stores what it needs to undo itself, so an AI reschedule that went
 * wrong (e.g. a misread timetable photo) can be reversed exactly.
 */
public interface ScheduleCommand {

    void execute();

    void undo();

    /** Shown to the user, e.g. "Rescheduled 12 waterings to fit your availability". */
    String description();
}
