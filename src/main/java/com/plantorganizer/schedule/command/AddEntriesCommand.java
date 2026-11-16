package com.plantorganizer.schedule.command;

import java.util.List;

import com.plantorganizer.schedule.CalendarEntry;
import com.plantorganizer.schedule.CalendarEntry.EntrySpec;
import com.plantorganizer.schedule.CalendarEvent;
import com.plantorganizer.schedule.ScheduleManager;

/** Concrete command: adds a new plant's waterings (F02). Undo removes them again. */
public class AddEntriesCommand implements ScheduleCommand {

    private final ScheduleManager receiver;
    private final long ownerId;
    private final String description;
    private final List<EntrySpec> entries;
    private List<Long> liveIds = List.of();

    public AddEntriesCommand(ScheduleManager receiver, long ownerId, String description, List<EntrySpec> entries) {
        this.receiver = receiver;
        this.ownerId = ownerId;
        this.description = description;
        this.entries = List.copyOf(entries);
    }

    @Override
    public void execute() {
        liveIds = receiver.replaceEntries(ownerId, List.of(), entries, CalendarEvent.Type.ENTRIES_ADDED)
                .stream().map(CalendarEntry::getId).toList();
    }

    @Override
    public void undo() {
        receiver.replaceEntries(ownerId, liveIds, List.of(), CalendarEvent.Type.UNDONE);
        liveIds = List.of();
    }

    @Override
    public String description() {
        return description;
    }
}
