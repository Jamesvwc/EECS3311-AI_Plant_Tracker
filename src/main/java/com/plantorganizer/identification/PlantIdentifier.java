package com.plantorganizer.identification;

import java.util.List;

/**
 * Target interface of the Adapter pattern for photo identification.
 *
 * The rest of the system asks "which plant is in these photos?" through this
 * interface only. PlantNetAdapter adapts the Pl@ntNet REST API to it, so
 * swapping identification services later means writing one new adapter
 * rather than editing the agent or the tools.
 */
public interface PlantIdentifier {

    /** Maximum photos per identification (Pl@ntNet accepts up to 5). */
    int MAX_IMAGES = 5;

    /**
     * Identifies the plant shown in the given photos (all of the same plant).
     *
     * @param images     prepared JPEG/PNG photos, 1 to {@link #MAX_IMAGES}
     * @param maxResults how many candidates to return
     * @return the candidates; an empty result when no plant was recognised
     */
    IdentificationResult identify(List<PlantImage> images, int maxResults);
}
