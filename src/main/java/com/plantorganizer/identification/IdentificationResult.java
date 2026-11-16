package com.plantorganizer.identification;

import java.util.List;

/**
 * What the identification step returns to the user: up to three candidate
 * species, best first. An empty list means no plant could be recognised, and
 * the GUI then asks for more or clearer photos.
 *
 * @param candidates        best guesses, sorted by descending score
 * @param photosUsed        how many photos were sent in this attempt
 * @param remainingRequests Pl@ntNet's remaining daily quota, or null if unknown
 * @param notes             what happened along the way (text path: which AI guesses were verified)
 */
public record IdentificationResult(List<PlantCandidate> candidates, int photosUsed, Integer remainingRequests,
                                   List<String> notes) {

    public IdentificationResult {
        candidates = candidates == null ? List.of() : List.copyOf(candidates);
        notes = notes == null ? List.of() : List.copyOf(notes);
    }

    public IdentificationResult(List<PlantCandidate> candidates, int photosUsed, Integer remainingRequests) {
        this(candidates, photosUsed, remainingRequests, List.of());
    }

    public boolean noMatch() {
        return candidates.isEmpty();
    }
}
