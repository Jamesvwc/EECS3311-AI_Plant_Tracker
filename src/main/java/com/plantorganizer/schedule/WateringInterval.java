package com.plantorganizer.schedule;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.plantorganizer.plants.CareNotes;
import com.plantorganizer.plants.IntervalQuality;

/**
 * Turns care data into "water every N days" and records how reliable that
 * number is, so the agent can tell the user when the data was thin (F02).
 */
public record WateringInterval(int days, IntervalQuality quality, String basis) {

    static final int DEFAULT_DAYS = 7;
    private static final Pattern RANGE = Pattern.compile("(\\d+)\\s*(?:-|to|–)?\\s*(\\d+)?\\s*(day|week)?");

    public static WateringInterval from(CareNotes care) {
        String frequency = care.getWateringFrequency();
        if (frequency != null) {
            Matcher m = RANGE.matcher(frequency.toLowerCase(Locale.ROOT));
            if (m.find()) {
                int low = Integer.parseInt(m.group(1));
                int high = m.group(2) != null ? Integer.parseInt(m.group(2)) : low;
                int multiplier = "week".equals(m.group(3)) ? 7 : 1;
                int days = clamp((low + high) / 2 * multiplier);
                return new WateringInterval(days, IntervalQuality.GOOD, "care data says " + frequency);
            }
        }
        String watering = care.getWatering();
        if (watering != null) {
            String w = watering.toLowerCase(Locale.ROOT);
            Integer days = w.contains("frequent") ? Integer.valueOf(4)
                    : w.contains("average") || w.contains("moderate") ? Integer.valueOf(7)
                    : w.contains("minimum") || w.contains("low") ? Integer.valueOf(14)
                    : w.contains("none") ? Integer.valueOf(21) : null;
            if (days != null) {
                return new WateringInterval(days, IntervalQuality.ESTIMATED,
                        "estimated from the watering category \"" + watering + "\"");
            }
        }
        return new WateringInterval(DEFAULT_DAYS, IntervalQuality.MISSING,
                "no watering information was found, so a 7-day default is used");
    }

    private static int clamp(int days) {
        return Math.max(1, Math.min(60, days));
    }
}
