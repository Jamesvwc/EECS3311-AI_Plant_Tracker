package com.plantorganizer.plants;

import java.util.Arrays;
import java.util.List;

import com.plantorganizer.plantinfo.InfoSource;
import com.plantorganizer.plantinfo.PlantCareProfile;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;

/**
 * The care "note" stored with each owned plant (F08). It starts as a copy of
 * the PlantCareProfile found during identification; fields the sources did
 * not know stay empty and the user can fill or correct any of them.
 */
@Embeddable
public class CareNotes {

    private String family;
    @Column(length = 4000)
    private String description;
    private String watering;
    private String wateringFrequency;
    /** Stored as "part shade; full sun". */
    private String sunlight;
    private String careLevel;
    private String growthRate;
    private Boolean indoor;
    private Boolean poisonousToPets;
    private Boolean poisonousToHumans;
    private String hardinessZones;
    @Column(length = 1000)
    private String imageUrl;
    @Enumerated(EnumType.STRING)
    private InfoSource source;
    private String similarPlantName;

    public CareNotes() {
    }

    public static CareNotes from(PlantCareProfile p) {
        CareNotes c = new CareNotes();
        if (p == null) {
            return c;
        }
        c.family = p.family();
        c.description = truncate(p.description(), 4000);
        c.watering = p.watering();
        c.wateringFrequency = p.wateringFrequency();
        c.sunlight = p.sunlight().isEmpty() ? null : String.join("; ", p.sunlight());
        c.careLevel = p.careLevel();
        c.growthRate = p.growthRate();
        c.indoor = p.indoor();
        c.poisonousToPets = p.poisonousToPets();
        c.poisonousToHumans = p.poisonousToHumans();
        c.hardinessZones = p.hardinessZones();
        c.imageUrl = truncate(p.imageUrl(), 1000);
        c.source = p.source();
        c.similarPlantName = p.similarPlantName();
        return c;
    }

    /** Back to the source-independent record the agent and the GUI use. */
    public PlantCareProfile toProfile(String scientificName, String commonName) {
        return new PlantCareProfile(scientificName, commonName, family, description, watering, wateringFrequency,
                sunlightList(), careLevel, growthRate, indoor, poisonousToPets, poisonousToHumans, hardinessZones,
                imageUrl, source, similarPlantName);
    }

    /** Applies the user's manual edits; a blank value clears the field. */
    public void applyEdits(PlantCareProfile edits) {
        family = blankToNull(edits.family());
        description = truncate(blankToNull(edits.description()), 4000);
        watering = blankToNull(edits.watering());
        wateringFrequency = blankToNull(edits.wateringFrequency());
        sunlight = edits.sunlight().isEmpty() ? null : String.join("; ", edits.sunlight());
        careLevel = blankToNull(edits.careLevel());
        growthRate = blankToNull(edits.growthRate());
        indoor = edits.indoor();
        poisonousToPets = edits.poisonousToPets();
        poisonousToHumans = edits.poisonousToHumans();
        hardinessZones = blankToNull(edits.hardinessZones());
    }

    public List<String> sunlightList() {
        if (sunlight == null || sunlight.isBlank()) {
            return List.of();
        }
        return Arrays.stream(sunlight.split(";")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    public String getWatering() {
        return watering;
    }

    public String getWateringFrequency() {
        return wateringFrequency;
    }

    public String getCareLevel() {
        return careLevel;
    }

    public String getImageUrl() {
        return imageUrl;
    }

    public String getFamily() {
        return family;
    }

    public void setWatering(String watering) {
        this.watering = watering;
    }

    public void setWateringFrequency(String wateringFrequency) {
        this.wateringFrequency = wateringFrequency;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static String truncate(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }
}
