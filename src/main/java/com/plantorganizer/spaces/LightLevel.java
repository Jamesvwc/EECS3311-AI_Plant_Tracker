package com.plantorganizer.spaces;

import java.util.List;
import java.util.Locale;

/** How much light a space gets (F10). Used by placement advice (F01) and the wishlist fit check (F09). */
public enum LightLevel {
    LOW("Low light"),
    MEDIUM("Medium light / part shade"),
    BRIGHT_INDIRECT("Bright indirect light"),
    DIRECT_SUN("Direct sun");

    private final String label;

    LightLevel(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }

    /**
     * Rough mapping of care-data sunlight values ("full sun", "part shade",
     * "bright indirect light"...) to light levels. Empty when unknown.
     */
    public static List<LightLevel> fromSunlight(List<String> sunlight) {
        java.util.EnumSet<LightLevel> levels = java.util.EnumSet.noneOf(LightLevel.class);
        for (String s : sunlight) {
            String v = s.toLowerCase(Locale.ROOT);
            if (v.contains("full shade") || v.contains("low light") || v.contains("deep shade")) {
                levels.add(LOW);
            }
            if (v.contains("part shade") || v.contains("filtered") || v.contains("part sun") || v.contains("medium")) {
                levels.add(MEDIUM);
            }
            if (v.contains("indirect") || v.contains("bright")) {
                levels.add(BRIGHT_INDIRECT);
            }
            if (v.contains("full sun") || v.contains("direct sun") || v.equals("sun")) {
                levels.add(DIRECT_SUN);
            }
        }
        return List.copyOf(levels);
    }
}
