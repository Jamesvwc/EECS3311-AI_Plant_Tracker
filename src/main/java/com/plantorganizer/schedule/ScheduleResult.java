package com.plantorganizer.schedule;

import java.time.LocalDateTime;
import java.util.List;

import com.plantorganizer.plants.IntervalQuality;

/**
 * F02 result: the plant's new watering schedule and how it was made.
 *
 * @param dataWarning  set when the care data was thin (estimated or missing interval)
 * @param explanation  the agent's explanation (LLM), filled in by PlantAgent
 * @param askAboutNotifications true the first time a schedule is made, so the GUI asks about reminders (F04)
 */
public record ScheduleResult(
        long plantId,
        String plantName,
        int intervalDays,
        IntervalQuality intervalQuality,
        String intervalBasis,
        String strategy,
        boolean grouped,
        List<String> groupedWith,
        String reason,
        List<LocalDateTime> nextWaterings,
        int entryCount,
        String dataWarning,
        String explanation,
        boolean askAboutNotifications) {

    public ScheduleResult withExplanation(String text) {
        return new ScheduleResult(plantId, plantName, intervalDays, intervalQuality, intervalBasis, strategy, grouped,
                groupedWith, reason, nextWaterings, entryCount, dataWarning, text, askAboutNotifications);
    }

    public ScheduleResult withAskAboutNotifications(boolean ask) {
        return new ScheduleResult(plantId, plantName, intervalDays, intervalQuality, intervalBasis, strategy, grouped,
                groupedWith, reason, nextWaterings, entryCount, dataWarning, explanation, ask);
    }
}
