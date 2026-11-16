package com.plantorganizer.plantinfo;

import java.util.List;

/**
 * Care information for one species in a single, source-independent shape.
 * Both PerenualAdapter and LlmCareInfoAdapter produce this record, which is
 * what lets PlantInfoService fall back from one to the other. Any field may
 * be null when the source did not know it; the GUI shows those as "Unknown".
 *
 * @param similarPlantName set only when the AI could not describe the exact
 *                         species and described this similar plant instead
 */
public record PlantCareProfile(
        String scientificName,
        String commonName,
        String family,
        String description,
        String watering,
        String wateringFrequency,
        List<String> sunlight,
        String careLevel,
        String growthRate,
        Boolean indoor,
        Boolean poisonousToPets,
        Boolean poisonousToHumans,
        String hardinessZones,
        String imageUrl,
        InfoSource source,
        String similarPlantName) {

    public PlantCareProfile {
        sunlight = sunlight == null ? List.of() : List.copyOf(sunlight);
    }

    public PlantCareProfile withImageUrlIfMissing(String fallbackImageUrl) {
        if (imageUrl != null || fallbackImageUrl == null) {
            return this;
        }
        return new PlantCareProfile(scientificName, commonName, family, description, watering,
                wateringFrequency, sunlight, careLevel, growthRate, indoor, poisonousToPets,
                poisonousToHumans, hardinessZones, fallbackImageUrl, source, similarPlantName);
    }
}
