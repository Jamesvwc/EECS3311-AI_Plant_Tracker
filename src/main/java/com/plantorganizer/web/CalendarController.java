package com.plantorganizer.web;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.plantorganizer.agent.AgentController;
import com.plantorganizer.common.CurrentUser;
import com.plantorganizer.common.InvalidInputException;
import com.plantorganizer.notification.WateringOverview;
import com.plantorganizer.schedule.CalendarItem;
import com.plantorganizer.schedule.RescheduleResult;
import com.plantorganizer.schedule.availability.Availability;
import com.plantorganizer.schedule.availability.AvailabilityInput;

/** HTTP boundary for the calendar page: F02 view, F03 availability + undo, F04 overview. */
@RestController
@RequestMapping("/api/calendar")
public class CalendarController {

    private final AgentController agent;

    public CalendarController(AgentController agent) {
        this.agent = agent;
    }

    @GetMapping
    public List<CalendarItem> calendar(@RequestHeader(name = CurrentUser.HEADER, defaultValue = CurrentUser.DEFAULT_ID) long userId,
                                       @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                       @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return agent.getCalendar(userId, from, to);
    }

    @GetMapping("/overview")
    public WateringOverview overview(@RequestHeader(name = CurrentUser.HEADER, defaultValue = CurrentUser.DEFAULT_ID) long userId) {
        return agent.getWateringOverview(userId);
    }

    @GetMapping("/availability")
    public Map<String, Object> availability(@RequestHeader(name = CurrentUser.HEADER, defaultValue = CurrentUser.DEFAULT_ID) long userId) {
        Availability a = agent.getAvailability(userId);
        return Map.of("availability", a, "lastChange", agent.lastScheduleChange(userId).orElse(""));
    }

    /** F03: multipart with either "text" or "file" (a timetable photo or an .ics export). */
    @PostMapping(path = "/availability", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public RescheduleResult updateAvailability(@RequestHeader(name = CurrentUser.HEADER, defaultValue = CurrentUser.DEFAULT_ID) long userId,
                                               @RequestParam(name = "text", required = false) String text,
                                               @RequestParam(name = "file", required = false) MultipartFile file) {
        AvailabilityInput input;
        if (file != null && !file.isEmpty()) {
            input = AvailabilityInput.file(Uploads.toImage(file));
        } else if (text != null && !text.isBlank()) {
            input = AvailabilityInput.text(text);
        } else {
            throw new InvalidInputException("Type when you're free, or upload a timetable photo or calendar (.ics) file.");
        }
        return agent.updateSchedule(userId, input);
    }

    @PostMapping("/undo")
    public Map<String, Object> undo(@RequestHeader(name = CurrentUser.HEADER, defaultValue = CurrentUser.DEFAULT_ID) long userId) {
        return agent.undoScheduleChange(userId)
                .<Map<String, Object>>map(d -> Map.of("undone", true, "message", "Undone: " + d + "."))
                .orElse(Map.of("undone", false, "message", "There's no schedule change to undo."));
    }
}
