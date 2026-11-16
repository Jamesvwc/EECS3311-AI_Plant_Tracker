package com.plantorganizer.spaces;

import java.util.ArrayList;
import java.util.List;

import com.plantorganizer.plants.OwnedPlant;

import jakarta.persistence.CascadeType;
import jakarta.persistence.DiscriminatorValue;
import jakarta.persistence.Entity;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;

/** Composite: a room, area or piece of furniture that contains other spaces. */
@Entity
@DiscriminatorValue("ROOM")
public class Room extends SpaceComponent {

    @OneToMany(mappedBy = "parent", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("id")
    private List<SpaceComponent> children = new ArrayList<>();

    protected Room() {
    }

    public Room(long ownerId, String name, LightLevel lightLevel) {
        super(ownerId, name, lightLevel);
    }

    /** Recursion of the Composite pattern: a room's plants are its children's plants. */
    @Override
    public List<OwnedPlant> getPlants() {
        List<OwnedPlant> all = new ArrayList<>();
        for (SpaceComponent child : children) {
            all.addAll(child.getPlants());
        }
        return all;
    }

    @Override
    public void add(SpaceComponent child) {
        child.setParent(this);
        children.add(child);
    }

    @Override
    public void remove(SpaceComponent child) {
        children.remove(child);
        child.setParent(null);
    }

    @Override
    public List<SpaceComponent> getChildren() {
        return List.copyOf(children);
    }

    @Override
    public boolean isComposite() {
        return true;
    }
}
