package com.plantorganizer.schedule.availability;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Concrete importer and Adapter (Stage 1 ICalAdapter): reads an iCalendar
 * (.ics) export from Google/Apple/Outlook calendars deterministically, with
 * no AI. Weekly recurring events (classes, work shifts) are treated as busy
 * time and turned into free windows between 07:00 and 22:00; all-day or
 * multi-day events (trips) become away periods.
 */
public class ICalAdapter implements AvailabilityImporter {

    static final LocalTime DAY_START = LocalTime.of(7, 0);
    static final LocalTime DAY_END = LocalTime.of(22, 0);
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss");
    private static final Map<String, DayOfWeek> BYDAY = Map.of("MO", DayOfWeek.MONDAY, "TU", DayOfWeek.TUESDAY,
            "WE", DayOfWeek.WEDNESDAY, "TH", DayOfWeek.THURSDAY, "FR", DayOfWeek.FRIDAY, "SA", DayOfWeek.SATURDAY,
            "SU", DayOfWeek.SUNDAY);

    private final Clock clock;

    ICalAdapter(Clock clock) {
        this.clock = clock;
    }

    record Busy(DayOfWeek day, LocalTime start, LocalTime end) {
    }

    @Override
    public Availability parse(AvailabilityInput input) {
        String text = input.file() != null ? new String(input.file().data(), StandardCharsets.UTF_8) : input.text();
        if (text == null || !text.contains("BEGIN:VCALENDAR")) {
            throw new ScheduleParseException("That file doesn't look like a calendar export (.ics). "
                    + "Export your calendar as iCal, or type when you're usually home.");
        }
        List<Map<String, String>> events = events(unfold(text));
        LocalDate today = LocalDate.now(clock);
        List<Busy> busy = new ArrayList<>();
        List<Availability.DateRange> away = new ArrayList<>();
        int oneOff = 0;
        for (Map<String, String> event : events) {
            String startRaw = event.get("DTSTART");
            if (startRaw == null) {
                continue;
            }
            boolean allDay = !startRaw.contains("T");
            LocalDateTime start = dateTime(startRaw, event.get("DTSTART;TZID"));
            String endRaw = event.get("DTEND");
            LocalDateTime end = endRaw != null ? dateTime(endRaw, event.get("DTEND;TZID"))
                    : start.plusHours(allDay ? 24 : 1);
            String summary = event.getOrDefault("SUMMARY", "Busy");
            String rrule = event.getOrDefault("RRULE", "");

            if (allDay || Duration.between(start, end).toHours() >= 24) {
                LocalDate last = allDay ? end.toLocalDate().minusDays(1) : end.toLocalDate();
                if (!last.isBefore(today)) {
                    away.add(new Availability.DateRange(start.toLocalDate(), last.isBefore(start.toLocalDate())
                            ? start.toLocalDate() : last, summary));
                }
            } else if (rrule.contains("FREQ=WEEKLY") || rrule.contains("FREQ=DAILY")) {
                for (DayOfWeek day : recurringDays(rrule, start.getDayOfWeek())) {
                    busy.add(new Busy(day, start.toLocalTime(), end.toLocalTime()));
                }
            } else {
                oneOff++;
            }
        }
        if (busy.isEmpty() && away.isEmpty()) {
            throw new ScheduleParseException("I couldn't find any repeating commitments or trips in that calendar. "
                    + "Could you type when you're usually home instead?");
        }
        List<Availability.TimeWindow> windows = freeWindows(busy);
        String summary = "From your calendar: " + busy.size() + " weekly commitment(s) and " + away.size()
                + " trip(s) away" + (oneOff > 0 ? " (" + oneOff + " one-off event(s) ignored)" : "") + ".";
        return new Availability(windows, away, 0, summary, "ICAL");
    }

    /** Free time on each day between 07:00 and 22:00, minus busy blocks; windows shorter than 30 minutes are dropped. */
    static List<Availability.TimeWindow> freeWindows(List<Busy> busy) {
        Map<DayOfWeek, List<Busy>> byDay = new EnumMap<>(DayOfWeek.class);
        busy.forEach(b -> byDay.computeIfAbsent(b.day(), d -> new ArrayList<>()).add(b));
        List<Availability.TimeWindow> windows = new ArrayList<>();
        for (DayOfWeek day : DayOfWeek.values()) {
            List<Busy> blocks = byDay.getOrDefault(day, List.of()).stream()
                    .sorted((a, b) -> a.start().compareTo(b.start())).toList();
            LocalTime cursor = DAY_START;
            for (Busy block : blocks) {
                if (block.start().isAfter(cursor)) {
                    addWindow(windows, day, cursor, block.start().isBefore(DAY_END) ? block.start() : DAY_END);
                }
                if (block.end().isAfter(cursor)) {
                    cursor = block.end();
                }
            }
            addWindow(windows, day, cursor, DAY_END);
        }
        return windows;
    }

    private static void addWindow(List<Availability.TimeWindow> windows, DayOfWeek day, LocalTime from, LocalTime to) {
        if (!from.isBefore(DAY_START) && Duration.between(from, to).toMinutes() >= 30) {
            windows.add(new Availability.TimeWindow(day, from, to));
        }
    }

    private static List<DayOfWeek> recurringDays(String rrule, DayOfWeek startDay) {
        if (rrule.contains("FREQ=DAILY")) {
            return List.of(DayOfWeek.values());
        }
        for (String part : rrule.split(";")) {
            if (part.startsWith("BYDAY=")) {
                return java.util.Arrays.stream(part.substring(6).split(","))
                        .map(d -> BYDAY.get(d.replaceAll("[^A-Z]", "")))
                        .filter(java.util.Objects::nonNull)
                        .collect(Collectors.toList());
            }
        }
        return List.of(startDay);
    }

    private static LocalDateTime dateTime(String value, String tzid) {
        String v = value.trim();
        if (!v.contains("T")) {
            return LocalDate.parse(v.substring(0, 8), DATE).atStartOfDay();
        }
        if (v.endsWith("Z")) {
            return LocalDateTime.parse(v.substring(0, 15), DATE_TIME).atOffset(ZoneOffset.UTC)
                    .atZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime();
        }
        LocalDateTime local = LocalDateTime.parse(v.substring(0, 15), DATE_TIME);
        if (tzid != null) {
            try {
                return local.atZone(ZoneId.of(tzid)).withZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime();
            } catch (RuntimeException ignored) {
                // unknown zone name: keep the local time
            }
        }
        return local;
    }

    /** Joins folded lines (RFC 5545: continuation lines start with a space or tab). */
    private static List<String> unfold(String text) {
        List<String> lines = new ArrayList<>();
        for (String raw : text.split("\\r?\\n")) {
            if ((raw.startsWith(" ") || raw.startsWith("\t")) && !lines.isEmpty()) {
                lines.set(lines.size() - 1, lines.get(lines.size() - 1) + raw.substring(1));
            } else {
                lines.add(raw);
            }
        }
        return lines;
    }

    /**
     * Collects each VEVENT's properties. "DTSTART;TZID=America/Toronto:2026..." is stored as
     * DTSTART=2026... plus DTSTART;TZID=America/Toronto.
     */
    private static List<Map<String, String>> events(List<String> lines) {
        List<Map<String, String>> events = new ArrayList<>();
        Map<String, String> current = null;
        for (String line : lines) {
            if (line.equals("BEGIN:VEVENT")) {
                current = new HashMap<>();
            } else if (line.equals("END:VEVENT") && current != null) {
                events.add(current);
                current = null;
            } else if (current != null) {
                int colon = line.indexOf(':');
                if (colon <= 0) {
                    continue;
                }
                String key = line.substring(0, colon);
                String value = line.substring(colon + 1);
                String[] keyParts = key.split(";");
                current.put(keyParts[0].toUpperCase(Locale.ROOT), value);
                for (int i = 1; i < keyParts.length; i++) {
                    if (keyParts[i].startsWith("TZID=")) {
                        current.put(keyParts[0] + ";TZID", keyParts[i].substring(5));
                    }
                }
            }
        }
        return events;
    }
}
