package com.plantorganizer.web;

import java.util.List;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.plantorganizer.agent.AgentController;
import com.plantorganizer.common.CurrentUser;
import com.plantorganizer.notification.Notification;

/** HTTP boundary for F04 reminders (the bell in the header). */
@RestController
@RequestMapping("/api/notifications")
public class NotificationController {

    private final AgentController agent;

    public NotificationController(AgentController agent) {
        this.agent = agent;
    }

    public record DefaultRequest(Boolean enabled, Long plantId) {
    }

    @GetMapping
    public List<Notification> list(@RequestHeader(name = CurrentUser.HEADER, defaultValue = CurrentUser.DEFAULT_ID) long userId) {
        return agent.listNotifications(userId);
    }

    @PostMapping("/read")
    public void markRead(@RequestHeader(name = CurrentUser.HEADER, defaultValue = CurrentUser.DEFAULT_ID) long userId) {
        agent.markNotificationsRead(userId);
    }

    /** The answer to the agent's first-time "Would you like reminders?" question. */
    @PostMapping(path = "/default", consumes = MediaType.APPLICATION_JSON_VALUE)
    public void setDefault(@RequestHeader(name = CurrentUser.HEADER, defaultValue = CurrentUser.DEFAULT_ID) long userId,
                           @RequestBody DefaultRequest r) {
        agent.setNotificationDefault(userId, Boolean.TRUE.equals(r.enabled()), r.plantId());
    }
}
