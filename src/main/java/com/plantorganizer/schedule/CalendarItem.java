package com.plantorganizer.schedule;

import java.time.LocalDateTime;

/** One calendar entry as shown on the Calendar page. */
public record CalendarItem(Long entryId, long plantId, String plantName, LocalDateTime dueAt, String status,
                           boolean grouped, LocalDateTime completedAt) {
}
