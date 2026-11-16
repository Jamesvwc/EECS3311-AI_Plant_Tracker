package com.plantorganizer.schedule;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface CalendarEntryJpaRepository extends JpaRepository<CalendarEntry, Long> {

    List<CalendarEntry> findByOwnerIdAndDueAtBetweenOrderByDueAt(long ownerId, LocalDateTime from, LocalDateTime to);

    List<CalendarEntry> findByPlantIdAndStatusOrderByDueAt(long plantId, CalendarEntry.Status status);

    List<CalendarEntry> findByOwnerIdAndStatusAndDueAtAfterOrderByDueAt(long ownerId, CalendarEntry.Status status,
                                                                         LocalDateTime after);

    List<CalendarEntry> findByOwnerIdAndStatusAndDueAtBeforeOrderByDueAt(long ownerId, CalendarEntry.Status status,
                                                                          LocalDateTime before);

    List<CalendarEntry> findByStatusAndDueAtLessThanEqualOrderByDueAt(CalendarEntry.Status status, LocalDateTime time);

    List<CalendarEntry> findByPlantIdOrderByDueAt(long plantId);

    void deleteByPlantId(long plantId);
}
