package com.plantorganizer.schedule.availability;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.plantorganizer.common.JsonValues;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** The JSON template the LLM fills for the text and photo parsers, and the code that reads it back. */
final class AvailabilityTemplate {

    private AvailabilityTemplate() {
    }

    static String instructions(LocalDate today) {
        return """
                You turn a person's description of their week into a JSON availability template for a
                plant-watering planner. Today is %s (%s).
                - weeklyWindows: times the person is FREE AT HOME and could water plants. Use day names
                  MONDAY..SUNDAY and 24-hour "HH:mm" times. If they only describe when they are busy
                  (work, classes), infer the free windows between 07:00 and 22:00.
                - awayPeriods: trips or travel when they are not home, as "YYYY-MM-DD" dates (inclusive).
                  Resolve relative dates ("next weekend") against today's date.
                - travelBufferMinutes: travel or commute time they say they need after getting home
                  before they can water (0 if not mentioned).
                - summary: one friendly sentence describing what you understood.
                If the input does not describe availability, or is too vague to place any watering,
                set understood to false and write one short clarificationQuestion.
                """.formatted(today, today.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.ENGLISH));
    }

    static JsonNode schema(JsonMapper mapper) {
        return mapper.readTree("""
                {
                  "type": "object",
                  "properties": {
                    "understood": {"type": "boolean"},
                    "clarificationQuestion": {"type": "string"},
                    "summary": {"type": "string"},
                    "travelBufferMinutes": {"type": "integer"},
                    "weeklyWindows": {"type": "array", "items": {"type": "object",
                      "properties": {"day": {"type": "string"}, "start": {"type": "string"}, "end": {"type": "string"}},
                      "required": ["day", "start", "end"]}},
                    "awayPeriods": {"type": "array", "items": {"type": "object",
                      "properties": {"start": {"type": "string"}, "end": {"type": "string"}, "reason": {"type": "string"}},
                      "required": ["start", "end"]}}
                  },
                  "required": ["understood", "summary", "weeklyWindows", "awayPeriods", "travelBufferMinutes"]
                }
                """);
    }

    /** Reads the template leniently; entries the model got wrong are skipped rather than failing everything. */
    static Availability read(JsonNode json, String source, String fallbackQuestion) {
        if (Boolean.FALSE.equals(JsonValues.bool(json.path("understood")))) {
            String question = JsonValues.text(json.path("clarificationQuestion"));
            throw new ScheduleParseException(question != null ? question : fallbackQuestion);
        }
        List<Availability.TimeWindow> windows = new ArrayList<>();
        for (JsonNode w : json.path("weeklyWindows")) {
            try {
                DayOfWeek day = DayOfWeek.valueOf(JsonValues.text(w.path("day")).toUpperCase(Locale.ROOT));
                LocalTime start = LocalTime.parse(pad(JsonValues.text(w.path("start"))));
                LocalTime end = LocalTime.parse(pad(JsonValues.text(w.path("end"))));
                if (end.isAfter(start)) {
                    windows.add(new Availability.TimeWindow(day, start, end));
                }
            } catch (RuntimeException ignored) {
                // skip a malformed window
            }
        }
        List<Availability.DateRange> away = new ArrayList<>();
        for (JsonNode p : json.path("awayPeriods")) {
            try {
                LocalDate start = LocalDate.parse(JsonValues.text(p.path("start")));
                LocalDate end = LocalDate.parse(JsonValues.text(p.path("end")));
                away.add(new Availability.DateRange(start, end.isBefore(start) ? start : end,
                        JsonValues.text(p.path("reason"))));
            } catch (DateTimeParseException | NullPointerException ignored) {
                // skip a malformed period
            }
        }
        int buffer = json.path("travelBufferMinutes").isNumber() ? json.path("travelBufferMinutes").intValue() : 0;
        if (windows.isEmpty() && away.isEmpty()) {
            throw new ScheduleParseException(fallbackQuestion);
        }
        return new Availability(windows, away, buffer, JsonValues.text(json.path("summary")), source);
    }

    private static String pad(String time) {
        return time != null && time.length() == 4 && time.charAt(1) == ':' ? "0" + time : time;
    }
}
