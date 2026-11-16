package com.plantorganizer.notification;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.plantorganizer.plants.OwnedPlant;
import com.plantorganizer.plants.PlantRepository;
import com.plantorganizer.plants.WateringStatus;
import com.plantorganizer.schedule.CalendarEntry;
import com.plantorganizer.schedule.CalendarEvent;
import com.plantorganizer.schedule.CalendarObserver;
import com.plantorganizer.schedule.ScheduleManager;
import com.plantorganizer.spaces.SpaceManager;

/**
 * Stage 1 NotificationService (F04), the concrete observer of the calendar.
 *
 * It registers itself with ScheduleManager, so every calendar change reaches
 * {@link #update} and reminders follow the calendar without the agent having
 * to manage them. A timer ({@link #checkDueEntries}) moves plants through the
 * watering states (State pattern) and sends a reminder when a plant becomes
 * due or a watering is missed, if reminders are enabled for that plant.
 */
@Service
public class NotificationService implements CalendarObserver {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("EEE MMM d 'at' HH:mm", Locale.ENGLISH);

    private final ScheduleManager scheduleManager;
    private final PlantRepository plantRepository;
    private final NotificationJpaRepository notifications;
    private final Clock clock;
    private final Duration overdueAfter;

    public NotificationService(ScheduleManager scheduleManager, PlantRepository plantRepository,
                               NotificationJpaRepository notifications, Clock clock,
                               @Value("${plantorganizer.notifications.overdue-after-hours:24}") long overdueAfterHours) {
        this.scheduleManager = scheduleManager;
        this.plantRepository = plantRepository;
        this.notifications = notifications;
        this.clock = clock;
        this.overdueAfter = Duration.ofHours(overdueAfterHours);
        scheduleManager.registerObserver(this);
    }

    /** Observer callback: keeps plant states and unread reminders consistent with the calendar. */
    @Override
    public void update(CalendarEvent event) {
        if (event.type() == CalendarEvent.Type.REMOVED) {
            event.plantIds().forEach(notifications::deleteByPlantId);
            return;
        }
        if (event.type() == CalendarEvent.Type.COMPLETED) {
            notifications.findByPlantIdInAndReadFlagFalse(event.plantIds()).forEach(n -> {
                n.markRead();
                notifications.save(n);
            });
        }
        // A reschedule or undo can move the next watering into the future (or back into the past).
        LocalDateTime now = LocalDateTime.now(clock);
        for (long plantId : event.plantIds()) {
            OwnedPlant plant = plantRepository.findById(plantId);
            WateringStatus before = plant.getWateringStatus();
            WateringStatus after = plant.checkDue(now, scheduleManager.nextDue(plantId).orElse(null), overdueAfter);
            if (before != after) {
                plantRepository.save(plant);
                if (after == WateringStatus.OK) {
                    notifications.findByPlantIdInAndReadFlagFalse(List.of(plantId)).forEach(n -> {
                        n.markRead();
                        notifications.save(n);
                    });
                }
            }
        }
    }

    /** Timer (every minute, and shortly after start-up): checks due entries and sends reminders. */
    @Scheduled(fixedDelayString = "${plantorganizer.notifications.check-interval-ms:60000}", initialDelay = 5000)
    @Transactional
    public List<Notification> checkDueEntries() {
        LocalDateTime now = LocalDateTime.now(clock);
        Map<Long, LocalDateTime> earliestByPlant = new LinkedHashMap<>();
        for (CalendarEntry entry : scheduleManager.getDueEntries(now)) {
            earliestByPlant.putIfAbsent(entry.getPlantId(), entry.getDueAt());
        }
        List<Notification> sent = new ArrayList<>();
        for (Map.Entry<Long, LocalDateTime> due : earliestByPlant.entrySet()) {
            OwnedPlant plant = plantRepository.findById(due.getKey());
            WateringStatus before = plant.getWateringStatus();
            WateringStatus after = plant.checkDue(now, due.getValue(), overdueAfter);
            if (before == after) {
                continue;
            }
            plantRepository.save(plant);
            Notification reminder = after == WateringStatus.DUE
                    ? new Notification(plant.getOwnerId(), plant.getId(), Notification.Type.DUE,
                            "Time to water " + plant.getDisplayName() + " (planned for " + due.getValue().format(TIME) + ").", now)
                    : new Notification(plant.getOwnerId(), plant.getId(), Notification.Type.MISSED,
                            "You missed watering " + plant.getDisplayName() + " on " + due.getValue().format(TIME)
                                    + ". Water it when you can, then mark it as watered.", now);
            send(plant, reminder).ifPresent(sent::add);
        }
        return sent;
    }

    /** Delivers (stores) a reminder only when reminders are enabled for the plant. */
    java.util.Optional<Notification> send(OwnedPlant plant, Notification notification) {
        if (!plant.isNotificationsEnabled()) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(notifications.save(notification));
    }

    @Transactional
    public void setEnabled(long userId, long plantId, boolean enabled) {
        OwnedPlant plant = plantRepository.findOwned(userId, plantId);
        plant.setNotificationsEnabled(enabled);
        plantRepository.save(plant);
    }

    /** Plants whose watering is due now but not yet overdue. */
    @Transactional(readOnly = true)
    public List<WateringOverview.Item> getDueNow(long userId) {
        LocalDateTime now = LocalDateTime.now(clock);
        return items(userId, scheduleManager.plannedBefore(userId, now.plusSeconds(1)), now, false);
    }

    /** Plants with a watering that was missed (past the overdue threshold). */
    @Transactional(readOnly = true)
    public List<WateringOverview.Item> getOverdue(long userId) {
        LocalDateTime now = LocalDateTime.now(clock);
        return items(userId, scheduleManager.plannedBefore(userId, now.plusSeconds(1)), now, true);
    }

    /** The next watering of each plant in the coming week. */
    @Transactional(readOnly = true)
    public List<WateringOverview.Item> getUpcoming(long userId) {
        LocalDateTime now = LocalDateTime.now(clock);
        Map<Long, OwnedPlant> plants = plantsById(userId);
        Map<Long, WateringOverview.Item> firstPerPlant = new LinkedHashMap<>();
        for (CalendarEntry e : scheduleManager.plannedBetween(userId, now.plusSeconds(1), now.plusDays(7))) {
            OwnedPlant p = plants.get(e.getPlantId());
            if (p != null) {
                firstPerPlant.putIfAbsent(p.getId(), item(p, e.getDueAt(), "UPCOMING"));
            }
        }
        return List.copyOf(firstPerPlant.values());
    }

    @Transactional(readOnly = true)
    public WateringOverview getWateringOverview(long userId) {
        return new WateringOverview(getDueNow(userId), getOverdue(userId), getUpcoming(userId),
                notifications.countByOwnerIdAndReadFlagFalse(userId));
    }

    @Transactional(readOnly = true)
    public List<Notification> list(long userId) {
        return notifications.findTop50ByOwnerIdOrderByCreatedAtDesc(userId);
    }

    @Transactional
    public void markAllRead(long userId) {
        notifications.findByOwnerIdAndReadFlagFalse(userId).forEach(n -> {
            n.markRead();
            notifications.save(n);
        });
    }

    private List<WateringOverview.Item> items(long userId, List<CalendarEntry> pastEntries, LocalDateTime now,
                                              boolean overdue) {
        Map<Long, OwnedPlant> plants = plantsById(userId);
        Map<Long, WateringOverview.Item> firstPerPlant = new LinkedHashMap<>();
        for (CalendarEntry e : pastEntries) {
            OwnedPlant p = plants.get(e.getPlantId());
            boolean isOverdue = !now.isBefore(e.getDueAt().plus(overdueAfter));
            if (p != null && isOverdue == overdue) {
                firstPerPlant.putIfAbsent(p.getId(), item(p, e.getDueAt(), overdue ? "OVERDUE" : "DUE"));
            }
        }
        return List.copyOf(firstPerPlant.values());
    }

    private Map<Long, OwnedPlant> plantsById(long userId) {
        Map<Long, OwnedPlant> map = new LinkedHashMap<>();
        plantRepository.findAll(userId).forEach(p -> map.put(p.getId(), p));
        return map;
    }

    private static WateringOverview.Item item(OwnedPlant p, LocalDateTime dueAt, String status) {
        return new WateringOverview.Item(p.getId(), p.getDisplayName(), dueAt, status, p.isNotificationsEnabled(),
                SpaceManager.pathOf(p));
    }
}
