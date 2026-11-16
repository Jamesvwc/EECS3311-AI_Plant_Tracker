package com.plantorganizer.agent.tools;

import java.util.Map;

import com.plantorganizer.notification.NotificationService;
import com.plantorganizer.schedule.ScheduleManager;
import com.plantorganizer.schedule.availability.AvailabilityInput;

/**
 * Concrete tool (Stage 1 CalendarTool): bridges the agent to the scheduling
 * subsystem.
 *
 * Actions: generate (F02), availability (F03), undo (F03), overview (F04),
 * water (F04), notifications (F04).
 */
public class CalendarTool implements Tool {

    private final ScheduleManager scheduleManager;
    private final NotificationService notificationService;

    CalendarTool(ScheduleManager scheduleManager, NotificationService notificationService) {
        this.scheduleManager = scheduleManager;
        this.notificationService = notificationService;
    }

    @Override
    public ToolType type() {
        return ToolType.CALENDAR;
    }

    @Override
    public Object execute(Map<String, Object> args) {
        long userId = ToolArgs.userId(args);
        return switch (ToolArgs.action(args)) {
            case "generate" -> scheduleManager.generate(userId, ToolArgs.required(args, "plantId", Long.class));
            case "availability" -> scheduleManager.applyAvailability(userId,
                    ToolArgs.required(args, "input", AvailabilityInput.class));
            case "undo" -> scheduleManager.undoLast(userId);
            case "overview" -> notificationService.getWateringOverview(userId);
            case "water" -> scheduleManager.recordWatering(userId, ToolArgs.required(args, "plantId", Long.class));
            case "notifications" -> {
                long plantId = ToolArgs.required(args, "plantId", Long.class);
                boolean enabled = ToolArgs.required(args, "enabled", Boolean.class);
                notificationService.setEnabled(userId, plantId, enabled);
                yield enabled;
            }
            default -> throw new IllegalArgumentException("Unknown calendar action");
        };
    }
}
