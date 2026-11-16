package com.plantorganizer.spaces;

import java.util.ArrayList;
import java.util.List;

import com.plantorganizer.plants.OwnedPlant;

import jakarta.persistence.DiscriminatorValue;
import jakarta.persistence.Entity;
import jakarta.persistence.OneToMany;

/** Leaf: an exact place where plants sit (a window sill, a corner, a shelf position). */
@Entity
@DiscriminatorValue("SPOT")
public class Spot extends SpaceComponent {

    @OneToMany(mappedBy = "spot")
    private List<OwnedPlant> plants = new ArrayList<>();

    protected Spot() {
    }

    public Spot(long ownerId, String name, LightLevel lightLevel) {
        super(ownerId, name, lightLevel);
    }

    @Override
    public List<OwnedPlant> getPlants() {
        return List.copyOf(plants);
    }

    /** Puts a plant here (UC10: a plant already placed elsewhere is moved). */
    public void assign(OwnedPlant plant) {
        if (plant.getSpot() != null && plant.getSpot() != this) {
            plant.getSpot().release(plant);
        }
        plant.setSpot(this);
        if (!plants.contains(plant)) {
            plants.add(plant);
        }
    }

    public void release(OwnedPlant plant) {
        plants.remove(plant);
        if (plant.getSpot() == this) {
            plant.setSpot(null);
        }
    }

    @Override
    public boolean isComposite() {
        return false;
    }
}
