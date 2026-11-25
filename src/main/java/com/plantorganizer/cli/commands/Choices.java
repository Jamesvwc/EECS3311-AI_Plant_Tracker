package com.plantorganizer.cli.commands;

import java.util.ArrayList;
import java.util.List;

/**
 * The fixed values some options and parameters take, given to picocli as
 * {@code completionCandidates} so Tab can offer them (see CommandCompleter).
 */
final class Choices {

    private Choices() {
    }

    static final class YesNo extends ArrayList<String> {
        YesNo() {
            super(List.of("yes", "no"));
        }
    }

    static final class Light extends ArrayList<String> {
        Light() {
            super(List.of("low", "medium", "bright-indirect", "direct-sun"));
        }
    }

    static final class Watering extends ArrayList<String> {
        Watering() {
            super(List.of("Frequent", "Average", "Minimum", "None"));
        }
    }

    static final class CareLevel extends ArrayList<String> {
        CareLevel() {
            super(List.of("Easy", "Medium", "Hard"));
        }
    }

    static final class GrowthRate extends ArrayList<String> {
        GrowthRate() {
            super(List.of("Low", "Medium", "High"));
        }
    }
}
