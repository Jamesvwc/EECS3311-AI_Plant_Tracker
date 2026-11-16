package com.plantorganizer.schedule.command;

import java.util.List;

import com.plantorganizer.schedule.CalendarEntry;
import com.plantorganizer.schedule.CalendarEntry.EntrySpec;
import com.plantorganizer.schedule.CalendarEvent;
import com.plantorganizer.schedule.ScheduleManager;

/**
 * Concrete command: replaces a set of planned waterings with new ones (F03,
 * or regenerating a plant's schedule). It keeps a copy of the old entries so
 * undo can put them back exactly.
 */
public class RescheduleCommand implements ScheduleCommand {

    private final ScheduleManager receiver;
    private final long ownerId;
    private final String description;
    private final List<EntrySpec> oldEntries;
    private final List<EntrySpec> newEntries;
    private List<Long> oldIds;
    private List<Long> newIds = List.of();

    public RescheduleCommand(ScheduleManager receiver, long ownerId, String description,
                             List<CalendarEntry> currentEntries, List<EntrySpec> newEntries) {
        this.receiver = receiver;
        this.ownerId = ownerId;
        this.description = description;
        this.oldEntries = currentEntries.stream().map(CalendarEntry::spec).toList();
        this.oldIds = currentEntries.stream().map(CalendarEntry::getId).toList();
        this.newEntries = List.copyOf(newEntries);
    }

    @Override
    public void execute() {
        newIds = ids(receiver.replaceEntries(ownerId, oldIds, newEntries, CalendarEvent.Type.RESCHEDULED));
    }

    @Override
    public void undo() {
        oldIds = ids(receiver.replaceEntries(ownerId, newIds, oldEntries, CalendarEvent.Type.UNDONE));
        newIds = List.of();
    }

    @Override
    public String description() {
        return description;
    }

    private static List<Long> ids(List<CalendarEntry> entries) {
        return entries.stream().map(CalendarEntry::getId).toList();
    }
}
