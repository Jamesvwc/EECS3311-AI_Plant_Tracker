package com.plantorganizer.spaces;

import java.util.List;

/**
 * JSON shape of a space for the Room Builder page: the node, the plants placed
 * directly in it (spots only) and its children, recursively.
 */
public record SpaceView(
        Long id,
        String kind,
        String name,
        LightLevel lightLevel,
        String effectiveLight,
        String path,
        double posX,
        double posY,
        int plantCount,
        List<PlacedPlant> plants,
        List<SpaceView> children) {

    /** A plant marker on the floor plan, coloured by watering state and flagged when its health needs attention. */
    public record PlacedPlant(Long id, String name, String wateringStatus, boolean needsAttention, int noteCount) {
    }
}
