package com.plantorganizer.schedule.availability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;

import org.junit.jupiter.api.Test;

import com.plantorganizer.identification.PlantImage;

/** F03: the deterministic iCal importer and the factory that picks it. */
class ICalAdapterTest {

    private final Clock clock = Clock.fixed(Instant.parse("2026-11-12T12:00:00Z"), ZoneId.systemDefault());

    private static AvailabilityInput ics(String body) {
        return AvailabilityInput.file(new PlantImage(("BEGIN:VCALENDAR\r\n" + body + "END:VCALENDAR\r\n")
                .getBytes(StandardCharsets.UTF_8), "school.ics", "text/calendar"));
    }

    @Test
    void weeklyClassesBecomeBusyTimeAndTripsBecomeAwayPeriods() {
        AvailabilityInput input = ics("""
                BEGIN:VEVENT\r
                SUMMARY:EECS3311\r
                DTSTART:20261102T100000\r
                DTEND:20261102T130000\r
                RRULE:FREQ=WEEKLY;BYDAY=MO,WE\r
                END:VEVENT\r
                BEGIN:VEVENT\r
                SUMMARY:Ski trip\r
                DTSTART;VALUE=DATE:20261228\r
                DTEND;VALUE=DATE:20270102\r
                END:VEVENT\r
                """);
        assertThat(input.type()).isEqualTo(AvailabilityInput.InputType.ICAL);

        Availability a = new ImporterFactory(null, null, null, clock).create(input.type()).parse(input);

        assertThat(a.windowsOn(DayOfWeek.MONDAY)).extracting(Availability.TimeWindow::start, Availability.TimeWindow::end)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(LocalTime.of(7, 0), LocalTime.of(10, 0)),
                        org.assertj.core.groups.Tuple.tuple(LocalTime.of(13, 0), LocalTime.of(22, 0)));
        assertThat(a.windowsOn(DayOfWeek.TUESDAY)).hasSize(1);
        assertThat(a.isAway(LocalDate.of(2027, 1, 1))).isTrue();
        assertThat(a.isAway(LocalDate.of(2027, 1, 2))).isFalse();
    }

    @Test
    void aFileWithoutUsableEventsAsksForClarification() {
        AvailabilityInput input = ics("");
        assertThatThrownBy(() -> new ICalAdapter(clock).parse(input))
                .isInstanceOf(ScheduleParseException.class)
                .hasMessageContaining("type when you're usually home");
    }

    @Test
    void imagesGoToTheImageParser() {
        AvailabilityInput photo = AvailabilityInput.file(new PlantImage(new byte[] {1}, "timetable.jpg", "image/jpeg"));
        assertThat(photo.type()).isEqualTo(AvailabilityInput.InputType.IMAGE);
        assertThat(new ImporterFactory(null, null, null, clock).create(photo.type())).isInstanceOf(ImageScheduleParser.class);
    }
}
