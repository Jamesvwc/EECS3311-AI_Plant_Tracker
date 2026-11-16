package com.plantorganizer.notification;

import java.time.LocalDateTime;
import java.util.List;

/**
 * What the user sees when they open a reminder (F04): plants due now, plants
 * whose watering was missed, and what is coming up, each with its time.
 */
public record WateringOverview(List<Item> dueNow, List<Item> overdue, List<Item> upcoming, long unreadCount) {

    public record Item(long plantId, String plantName, LocalDateTime dueAt, String status,
                       boolean notificationsEnabled, String location) {
    }
}
