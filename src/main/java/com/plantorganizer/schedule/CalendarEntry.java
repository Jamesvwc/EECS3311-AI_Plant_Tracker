package com.plantorganizer.schedule;

import java.time.LocalDateTime;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;

/** One planned watering in the built-in calendar (F02). */
@Entity
public class CalendarEntry {

    public enum Status { PLANNED, DONE }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private long ownerId;
    private long plantId;
    private LocalDateTime dueAt;
    @Enumerated(EnumType.STRING)
    private Status status = Status.PLANNED;
    /** True when this watering was lined up with another plant's (GroupedWateringStrategy). */
    private boolean grouped;
    private LocalDateTime completedAt;

    protected CalendarEntry() {
    }

    public CalendarEntry(long ownerId, long plantId, LocalDateTime dueAt, boolean grouped) {
        this.ownerId = ownerId;
        this.plantId = plantId;
        this.dueAt = dueAt;
        this.grouped = grouped;
    }

    /** What a command needs to recreate this entry after it was deleted (undo). */
    public EntrySpec spec() {
        return new EntrySpec(ownerId, plantId, dueAt, grouped);
    }

    public void complete(LocalDateTime when) {
        this.status = Status.DONE;
        this.completedAt = when;
    }

    public Long getId() {
        return id;
    }

    public long getOwnerId() {
        return ownerId;
    }

    public long getPlantId() {
        return plantId;
    }

    public LocalDateTime getDueAt() {
        return dueAt;
    }

    public Status getStatus() {
        return status;
    }

    public boolean isGrouped() {
        return grouped;
    }

    public LocalDateTime getCompletedAt() {
        return completedAt;
    }

    /** Immutable description of an entry, used by the undoable commands. */
    public record EntrySpec(long ownerId, long plantId, LocalDateTime dueAt, boolean grouped) {

        public CalendarEntry toEntry() {
            return new CalendarEntry(ownerId, plantId, dueAt, grouped);
        }
    }
}
