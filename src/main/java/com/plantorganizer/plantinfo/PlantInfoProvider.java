package com.plantorganizer.plantinfo;

import java.util.List;
import java.util.Optional;

import com.plantorganizer.common.ExternalServiceException;
import com.plantorganizer.identification.PlantCandidate;

/**
 * Target interface of the Adapter pattern for care-information sources.
 *
 * PerenualAdapter (a plant database) and LlmCareInfoAdapter (an LLM filling a
 * care template) look identical through this interface, which is what lets
 * PlantInfoService try one and fall back to the other without knowing which
 * is which.
 */
public interface PlantInfoProvider {

    /** Short name used in logs and user-facing notes, e.g. "Perenual". */
    String name();

    /**
     * Looks up care information for the queried species.
     *
     * @return the profile, or empty when the source has no record of the species
     * @throws ExternalServiceException when the source could not be asked
     *                                  (network error, bad key, plan limits...)
     */
    Optional<PlantCareProfile> getCareProfile(PlantQuery query);

    /**
     * Searches the source's catalogue by name (scientific or common). Used to
     * check that species proposed by the AI really exist (F01 text path, F06/F07).
     * Sources without a searchable catalogue return an empty list.
     */
    default List<PlantCandidate> searchByName(String name) {
        return List.of();
    }
}
