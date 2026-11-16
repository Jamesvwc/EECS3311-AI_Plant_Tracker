package com.plantorganizer.spaces;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;

import com.plantorganizer.plants.CareNotes;
import com.plantorganizer.plants.OwnedPlant;

/** F10: the Composite pattern (rooms contain rooms and spots; any node lists all plants below it). */
class SpaceCompositeTest {

    private static OwnedPlant plant(String name) {
        return new OwnedPlant(1, name, name, null, new CareNotes(), LocalDateTime.now());
    }

    @Test
    void anyLevelListsEveryPlantInsideItAndInheritsLight() {
        Room home = new Room(1, "Home", LightLevel.MEDIUM);
        Room bedroom = new Room(1, "Bedroom", null);
        Spot sill = new Spot(1, "Window sill", LightLevel.DIRECT_SUN);
        Spot corner = new Spot(1, "Corner", null);
        home.add(bedroom);
        bedroom.add(sill);
        bedroom.add(corner);
        sill.assign(plant("Aloe vera"));
        corner.assign(plant("Dracaena trifasciata"));

        assertThat(home.getPlants()).extracting(OwnedPlant::getScientificName)
                .containsExactlyInAnyOrder("Aloe vera", "Dracaena trifasciata");
        assertThat(sill.getPlants()).hasSize(1);
        assertThat(corner.effectiveLight()).isEqualTo(LightLevel.MEDIUM);
        assertThat(sill.path()).isEqualTo("Home > Bedroom > Window sill");
    }

    @Test
    void assigningAPlacedPlantMovesIt() {
        Spot a = new Spot(1, "A", null);
        Spot b = new Spot(1, "B", null);
        OwnedPlant p = plant("Ficus lyrata");
        a.assign(p);
        b.assign(p);
        assertThat(a.getPlants()).isEmpty();
        assertThat(b.getPlants()).containsExactly(p);
    }

    @Test
    void spotsAreLeavesAndCannotContainSpaces() {
        assertThatThrownBy(() -> new Spot(1, "Shelf", null).add(new Spot(1, "Pot", null)))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
