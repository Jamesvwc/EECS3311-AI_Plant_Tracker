package com.plantorganizer.identification;

import java.util.List;

/**
 * One guess from the identification service: a species with its confidence
 * score (0.0 to 1.0) and, when available, a reference photo so the user can
 * compare it with their own plant before choosing it.
 */
public record PlantCandidate(
        String scientificName,
        String authorship,
        List<String> commonNames,
        String genus,
        String family,
        double score,
        String referenceImageUrl) {

    public PlantCandidate {
        commonNames = commonNames == null ? List.of() : List.copyOf(commonNames);
    }

    /** The first common name, or null when the species has none. */
    public String primaryCommonName() {
        return commonNames.isEmpty() ? null : commonNames.get(0);
    }
}
