package com.plantorganizer.agent;

import java.time.LocalDateTime;

import com.plantorganizer.plantinfo.PlantCareProfile;
import com.plantorganizer.plants.HealthStatus;
import com.plantorganizer.plants.IntervalQuality;
import com.plantorganizer.plants.WateringStatus;

/** JSON shape of an owned plant for the My Plants page (F08). */
public record OwnedPlantView(
        long id,
        String name,
        String nickname,
        String scientificName,
        String commonName,
        PlantCareProfile care,
        Integer wateringIntervalDays,
        IntervalQuality intervalQuality,
        WateringStatus wateringStatus,
        LocalDateTime lastWateredAt,
        LocalDateTime nextWatering,
        boolean notificationsEnabled,
        Long spaceId,
        String location,
        HealthStatus latestHealth,
        LocalDateTime addedAt) {
}
