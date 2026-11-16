package com.plantorganizer.agent;

import java.time.LocalDateTime;
import java.util.List;

import com.plantorganizer.plantinfo.PlantQuery;
import com.plantorganizer.plants.DesiredPlant;
import com.plantorganizer.plants.OwnedPlant;
import com.plantorganizer.plants.PlantRepository;
import com.plantorganizer.spaces.SpaceComponent;

/** Shared by the GUI facade and the chat agent: runs the F09 fit check and records the verdict. */
final class DesiredFitRequests {

    private DesiredFitRequests() {
    }

    static DesiredFit check(PlantAgent agent, PlantRepository plantRepository, long userId, DesiredPlant desired,
                            SpaceComponent space, LocalDateTime now) {
        List<String> neighbours = space.getPlants().stream().map(OwnedPlant::getDisplayName).toList();
        AgentRequest request = AgentRequest.checkDesiredFit(userId,
                PlantQuery.of(desired.getScientificName(), desired.getCommonName()), space.path(),
                space.effectiveLight() != null ? space.effectiveLight().name() : null,
                neighbours.isEmpty() ? List.of("none") : neighbours);
        DesiredFit fit = agent.run(request).resultAs(DesiredFit.class);
        desired.recordFit(fit.verdict(), fit.explanation(), now);
        plantRepository.saveDesired(desired);
        return fit;
    }
}
