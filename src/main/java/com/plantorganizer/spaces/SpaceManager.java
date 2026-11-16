package com.plantorganizer.spaces;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.plantorganizer.common.InvalidInputException;
import com.plantorganizer.plants.HealthRecord;
import com.plantorganizer.plants.OwnedPlant;
import com.plantorganizer.plants.PlantRepository;

/**
 * Stage 1 SpaceManager (F10): builds the user's space tree and places plants
 * in it. Works with the Composite through {@link SpaceComponent} only, except
 * where the type matters (only rooms can contain spaces, only spots hold plants).
 */
@Service
@Transactional
public class SpaceManager {

    static final String GENERAL_AREA = "General area";

    private final SpaceRepository spaceRepository;
    private final PlantRepository plantRepository;

    public SpaceManager(SpaceRepository spaceRepository, PlantRepository plantRepository) {
        this.spaceRepository = spaceRepository;
        this.plantRepository = plantRepository;
    }

    /**
     * Creates a room (composite) or a spot (leaf) under an optional parent room.
     * Top-level spaces must be rooms.
     */
    public SpaceView createSpace(long userId, String name, LightLevel light, Long parentId, boolean spot) {
        if (name == null || name.isBlank()) {
            throw new InvalidInputException("Give the space a name, e.g. \"Living room\" or \"Window sill\".");
        }
        SpaceComponent space = spot ? new Spot(userId, name.trim(), light) : new Room(userId, name.trim(), light);
        if (parentId != null) {
            SpaceComponent parent = spaceRepository.findOwned(userId, parentId);
            if (!parent.isComposite()) {
                throw new InvalidInputException("\"" + parent.getName() + "\" is a spot; spaces can only be added to rooms.");
            }
            parent.add(space);
            space.moveTo(10 + 25 * (parent.getChildren().size() - 1) % 80, 15 + 30 * ((parent.getChildren().size() - 1) / 4 % 3));
        } else if (spot) {
            throw new InvalidInputException("A spot must be inside a room. Create a room first.");
        }
        return toView(spaceRepository.save(space));
    }

    /** Places (or moves) a plant. Assigning to a room puts it in that room's "General area" spot. */
    public SpaceView assignPlant(long userId, long plantId, long spaceId) {
        OwnedPlant plant = plantRepository.findOwned(userId, plantId);
        SpaceComponent target = spaceRepository.findOwned(userId, spaceId);
        Spot spot = target instanceof Spot s ? s : generalArea((Room) target);
        spot.assign(plant);
        plantRepository.save(plant);
        return toView(spot);
    }

    public void unassignPlant(long userId, long plantId) {
        OwnedPlant plant = plantRepository.findOwned(userId, plantId);
        if (plant.getSpot() != null) {
            plant.getSpot().release(plant);
            plantRepository.save(plant);
        }
    }

    /** All plants in a space and everything inside it (Composite recursion). */
    @Transactional(readOnly = true)
    public List<OwnedPlant> getPlants(long userId, long spaceId) {
        return spaceRepository.findOwned(userId, spaceId).getPlants();
    }

    @Transactional(readOnly = true)
    public List<SpaceView> listSpaces(long userId) {
        return spaceRepository.findRoots(userId).stream().map(this::toView).toList();
    }

    @Transactional(readOnly = true)
    public SpaceComponent getSpace(long userId, long spaceId) {
        return spaceRepository.findOwned(userId, spaceId);
    }

    @Transactional(readOnly = true)
    public SpaceView getSpaceView(long userId, long spaceId) {
        return toView(spaceRepository.findOwned(userId, spaceId));
    }

    /** One line per space, e.g. "Home > Bedroom > Window sill (Bright indirect light; plants: Monstera)", for LLM prompts. */
    @Transactional(readOnly = true)
    public List<String> describeSpaces(long userId) {
        List<String> lines = new ArrayList<>();
        for (SpaceComponent root : spaceRepository.findRoots(userId)) {
            describe(root, lines);
        }
        return lines;
    }

    /** Finds a space by name for the agent ("my snake plant is on the bedroom shelf"). */
    @Transactional(readOnly = true)
    public Optional<SpaceComponent> findByName(long userId, String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        String wanted = name.toLowerCase(Locale.ROOT).replaceAll("\\b(the|my|a)\\b", "").trim();
        List<SpaceComponent> all = spaceRepository.findAll(userId);
        Optional<SpaceComponent> exact = all.stream()
                .filter(s -> s.getName().equalsIgnoreCase(wanted)).findFirst();
        if (exact.isPresent()) {
            return exact;
        }
        return all.stream()
                .filter(s -> s.path().toLowerCase(Locale.ROOT).contains(wanted)
                        || wanted.contains(s.getName().toLowerCase(Locale.ROOT)))
                .max((a, b) -> Integer.compare(a.getName().length(), b.getName().length()));
    }

    public SpaceView updateSpace(long userId, long spaceId, String name, LightLevel light, boolean inheritLight) {
        SpaceComponent space = spaceRepository.findOwned(userId, spaceId);
        if (name != null && !name.isBlank()) {
            space.setName(name.trim());
        }
        if (inheritLight) {
            space.setLightLevel(null);
        } else if (light != null) {
            space.setLightLevel(light);
        }
        return toView(spaceRepository.save(space));
    }

    /** Zoom-in floor plan: remembers where a space sits inside its parent (percent coordinates). */
    public void moveSpace(long userId, long spaceId, double x, double y) {
        SpaceComponent space = spaceRepository.findOwned(userId, spaceId);
        space.moveTo(x, y);
        spaceRepository.save(space);
    }

    /** Deletes a space and everything inside it; its plants become unplaced, not deleted. */
    public void deleteSpace(long userId, long spaceId) {
        SpaceComponent space = spaceRepository.findOwned(userId, spaceId);
        for (OwnedPlant plant : space.getPlants()) {
            plant.getSpot().release(plant);
            plantRepository.save(plant);
        }
        if (space.getParent() != null) {
            space.getParent().remove(space);
        }
        spaceRepository.delete(space);
    }

    public static String pathOf(OwnedPlant plant) {
        return plant.getSpot() == null ? null : plant.getSpot().path();
    }

    private Spot generalArea(Room room) {
        for (SpaceComponent child : room.getChildren()) {
            if (child instanceof Spot s && GENERAL_AREA.equals(s.getName())) {
                return s;
            }
        }
        Spot spot = new Spot(room.getOwnerId(), GENERAL_AREA, null);
        room.add(spot);
        return (Spot) spaceRepository.save(spot);
    }

    private void describe(SpaceComponent space, List<String> lines) {
        LightLevel light = space.effectiveLight();
        List<OwnedPlant> plants = space instanceof Spot ? space.getPlants() : List.of();
        StringBuilder line = new StringBuilder(space.path())
                .append(" (").append(light != null ? light.getLabel() : "light not set");
        if (!plants.isEmpty()) {
            line.append("; plants: ").append(String.join(", ", plants.stream().map(OwnedPlant::getDisplayName).toList()));
        }
        lines.add(line.append(')').toString());
        for (SpaceComponent child : space.getChildren()) {
            describe(child, lines);
        }
    }

    SpaceView toView(SpaceComponent space) {
        List<SpaceView.PlacedPlant> placed = new ArrayList<>();
        if (space instanceof Spot) {
            for (OwnedPlant p : space.getPlants()) {
                List<HealthRecord> records = plantRepository.findHealthRecords(p.getId());
                boolean attention = !records.isEmpty() && records.get(0).getStatus() != null
                        && records.get(0).getStatus().needsAttention();
                placed.add(new SpaceView.PlacedPlant(p.getId(), p.getDisplayName(), p.getWateringStatus().name(),
                        attention, plantRepository.findNotes(p.getId()).size()));
            }
        }
        LightLevel effective = space.effectiveLight();
        return new SpaceView(space.getId(), space.isComposite() ? "ROOM" : "SPOT", space.getName(),
                space.getLightLevel(), effective != null ? effective.getLabel() : null, space.path(),
                space.getPosX(), space.getPosY(), space.getPlants().size(), placed,
                space.getChildren().stream().map(this::toView).toList());
    }
}
