package com.plantorganizer.agent.tools;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Map;

import com.plantorganizer.common.InvalidInputException;
import com.plantorganizer.memory.AgentMemory;
import com.plantorganizer.memory.UserPreference;
import com.plantorganizer.plantinfo.CareLookup;
import com.plantorganizer.plantinfo.PlantCareProfile;
import com.plantorganizer.plants.CareNotes;
import com.plantorganizer.plants.DesiredPlant;
import com.plantorganizer.plants.OwnedPlant;
import com.plantorganizer.plants.PlantRepository;
import com.plantorganizer.recommendation.RecommendationEngine;
import com.plantorganizer.spaces.SpaceManager;
import com.plantorganizer.user.User;
import com.plantorganizer.user.UserRepository;

/**
 * Concrete tool (new in Stage 2): lets the agent add plants to the owned list
 * (F08) and the wishlist (F09), and update a plant's watering data after
 * research. Stage 1 had AgentController save plants directly; routing it
 * through a tool lets the chat box (CLI) and the GUI share one path.
 *
 * Actions: addOwned, addDesired, updateWatering.
 */
public class PlantCollectionTool implements Tool {

    private final PlantRepository plantRepository;
    private final SpaceManager spaceManager;
    private final AgentMemory memory;
    private final RecommendationEngine recommendationEngine;
    private final UserRepository userRepository;
    private final Clock clock;

    PlantCollectionTool(PlantRepository plantRepository, SpaceManager spaceManager, AgentMemory memory,
                        RecommendationEngine recommendationEngine, UserRepository userRepository, Clock clock) {
        this.plantRepository = plantRepository;
        this.spaceManager = spaceManager;
        this.memory = memory;
        this.recommendationEngine = recommendationEngine;
        this.userRepository = userRepository;
        this.clock = clock;
    }

    @Override
    public ToolType type() {
        return ToolType.PLANT_COLLECTION;
    }

    @Override
    public Object execute(Map<String, Object> args) {
        long userId = ToolArgs.userId(args);
        return switch (ToolArgs.action(args)) {
            case "addOwned" -> addOwned(userId, args);
            case "addDesired" -> addDesired(userId, args);
            case "updateWatering" -> updateWatering(userId, args);
            default -> throw new IllegalArgumentException("Unknown plant collection action");
        };
    }

    /**
     * Rejects a name that no source recognises as a plant species. The care
     * lookup asks Perenual first and then the LLM; the LLM marks names it does
     * not know (it then describes a "similar plant" instead). When both say
     * "unknown" there is no such plant, so nothing is saved. When a source
     * could not be reached the lookup is missing and the plant is still added
     * with empty care notes (UC02), since the name may well be real.
     */
    static void rejectUnknownSpecies(String name, PlantCareProfile profile) {
        if (profile != null && profile.similarPlantName() != null) {
            throw new InvalidInputException("No plant called \"" + name + "\" could be found. Check the scientific "
                    + "name (genus and species, e.g. \"Monstera deliciosa\") and try again.");
        }
    }

    private OwnedPlant addOwned(long userId, Map<String, Object> args) {
        CareLookup lookup = ToolArgs.optional(args, "lookup", CareLookup.class);
        PlantCareProfile profile = lookup != null ? lookup.profile() : null;
        String scientific = ToolArgs.required(args, "scientificName", String.class);
        rejectUnknownSpecies(scientific, profile);
        String common = ToolArgs.optional(args, "commonName", String.class);
        if (common == null && profile != null) {
            common = profile.commonName();
        }
        OwnedPlant plant = new OwnedPlant(userId, scientific, common, ToolArgs.optional(args, "nickname", String.class),
                CareNotes.from(profile), LocalDateTime.now(clock));
        User user = userRepository.findById(userId);
        plant.setNotificationsEnabled(user.isNotificationsAsked() && user.isNotifyByDefault());
        plant = plantRepository.save(plant);

        Long spaceId = ToolArgs.optional(args, "spaceId", Long.class);
        if (spaceId != null) {
            spaceManager.assignPlant(userId, plant.getId(), spaceId);
        }
        String family = profile != null && profile.family() != null ? ", family " + profile.family() : "";
        memory.savePreference(userId, UserPreference.Kind.LIKE, "owns " + plant.getDisplayName() + " (" + scientific + family + ")");
        recommendationEngine.markStale(userId);
        return plantRepository.findById(plant.getId());
    }

    private DesiredPlant addDesired(long userId, Map<String, Object> args) {
        CareLookup lookup = ToolArgs.optional(args, "lookup", CareLookup.class);
        PlantCareProfile profile = lookup != null ? lookup.profile() : null;
        String scientific = ToolArgs.required(args, "scientificName", String.class);
        rejectUnknownSpecies(scientific, profile);
        String common = ToolArgs.optional(args, "commonName", String.class);
        if (common == null && profile != null) {
            common = profile.commonName();
        }
        DesiredPlant plant = new DesiredPlant(userId, scientific,
                common, ToolArgs.optional(args, "spaceId", Long.class),
                ToolArgs.optional(args, "note", String.class), ToolArgs.optional(args, "imageUrl", String.class),
                LocalDateTime.now(clock));
        memory.savePreference(userId, UserPreference.Kind.LIKE, "wants " + plant.getDisplayName()
                + " (" + plant.getScientificName() + ")");
        return plantRepository.saveDesired(plant);
    }

    private OwnedPlant updateWatering(long userId, Map<String, Object> args) {
        OwnedPlant plant = plantRepository.findOwned(userId, ToolArgs.required(args, "plantId", Long.class));
        String watering = ToolArgs.optional(args, "watering", String.class);
        String frequency = ToolArgs.optional(args, "wateringFrequency", String.class);
        if (watering != null) {
            plant.getCare().setWatering(watering);
        }
        if (frequency != null) {
            plant.getCare().setWateringFrequency(frequency);
        }
        return plantRepository.save(plant);
    }
}
