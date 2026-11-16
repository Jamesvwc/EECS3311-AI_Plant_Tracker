package com.plantorganizer.spaces;

import java.util.List;

import com.plantorganizer.plants.OwnedPlant;

import jakarta.persistence.DiscriminatorColumn;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Inheritance;
import jakarta.persistence.InheritanceType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/**
 * Composite pattern, component role (F10). A user's home is a tree: rooms can
 * contain rooms (a shelf inside a bedroom) and spots, and spots hold plants.
 * Callers treat every node the same way: {@link #getPlants()} on any node
 * returns all plants below it, so selecting "Apartment" or "Window sill" uses
 * the same code.
 *
 * Stored in one table ("space") with a "kind" column saying ROOM or SPOT.
 */
@Entity
@Table(name = "space")
@Inheritance(strategy = InheritanceType.SINGLE_TABLE)
@DiscriminatorColumn(name = "kind")
public abstract class SpaceComponent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private long ownerId;
    private String name;
    /** Null means "same light as the parent space". */
    @Enumerated(EnumType.STRING)
    private LightLevel lightLevel;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_id")
    private Room parent;

    /** Position (0-100 %) inside the parent, for the zoomed-in floor plan. */
    private double posX = 10;
    private double posY = 10;

    protected SpaceComponent() {
    }

    protected SpaceComponent(long ownerId, String name, LightLevel lightLevel) {
        this.ownerId = ownerId;
        this.name = name;
        this.lightLevel = lightLevel;
    }

    /** All plants in this space and everything inside it. */
    public abstract List<OwnedPlant> getPlants();

    /** Adds a child space. Only composites (rooms) support this. */
    public void add(SpaceComponent child) {
        throw new UnsupportedOperationException("A spot cannot contain other spaces; add it to a room instead.");
    }

    public void remove(SpaceComponent child) {
        throw new UnsupportedOperationException("A spot has no child spaces.");
    }

    public List<SpaceComponent> getChildren() {
        return List.of();
    }

    public abstract boolean isComposite();

    /** The space's own light level, or the nearest ancestor's. */
    public LightLevel effectiveLight() {
        if (lightLevel != null) {
            return lightLevel;
        }
        return parent != null ? parent.effectiveLight() : null;
    }

    /** "Apartment > Bedroom > Window sill". */
    public String path() {
        return parent == null ? name : parent.path() + " > " + name;
    }

    void setParent(Room parent) {
        this.parent = parent;
    }

    public Long getId() {
        return id;
    }

    public long getOwnerId() {
        return ownerId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public LightLevel getLightLevel() {
        return lightLevel;
    }

    public void setLightLevel(LightLevel lightLevel) {
        this.lightLevel = lightLevel;
    }

    public Room getParent() {
        return parent;
    }

    public double getPosX() {
        return posX;
    }

    public double getPosY() {
        return posY;
    }

    public void moveTo(double x, double y) {
        this.posX = Math.max(0, Math.min(100, x));
        this.posY = Math.max(0, Math.min(100, y));
    }
}
