package com.plantorganizer.sharing;

/** F11: the names-only view of a plant that friends may see (no health or watering data). */
public record PlantSummary(String name, String scientificName) {
}
