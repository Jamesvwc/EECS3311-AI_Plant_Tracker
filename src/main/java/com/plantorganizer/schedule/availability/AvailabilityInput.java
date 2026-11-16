package com.plantorganizer.schedule.availability;

import java.util.Locale;

import com.plantorganizer.identification.PlantImage;

/** Raw availability from the user: typed text, a photo of a timetable, or an iCal (.ics) file. */
public record AvailabilityInput(InputType type, String text, PlantImage file) {

    public enum InputType { TEXT, IMAGE, ICAL }

    public static AvailabilityInput text(String text) {
        return new AvailabilityInput(InputType.TEXT, text, null);
    }

    /** Decides whether an uploaded file is an iCal file or an image. */
    public static AvailabilityInput file(PlantImage file) {
        String name = file.filename() == null ? "" : file.filename().toLowerCase(Locale.ROOT);
        String type = file.contentType() == null ? "" : file.contentType().toLowerCase(Locale.ROOT);
        boolean ical = name.endsWith(".ics") || name.endsWith(".ical") || type.contains("calendar");
        return new AvailabilityInput(ical ? InputType.ICAL : InputType.IMAGE, null, file);
    }
}
