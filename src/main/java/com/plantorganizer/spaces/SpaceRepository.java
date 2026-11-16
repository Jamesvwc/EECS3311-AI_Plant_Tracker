package com.plantorganizer.spaces;

import java.util.List;

import org.springframework.stereotype.Repository;

import com.plantorganizer.common.ForbiddenException;
import com.plantorganizer.common.NotFoundException;

/** Stage 1 SpaceRepository: saves and loads the space tree. */
@Repository
public class SpaceRepository {

    private final SpaceJpaRepository spaces;

    public SpaceRepository(SpaceJpaRepository spaces) {
        this.spaces = spaces;
    }

    public SpaceComponent save(SpaceComponent space) {
        return spaces.save(space);
    }

    public SpaceComponent findOwned(long ownerId, long spaceId) {
        SpaceComponent space = spaces.findById(spaceId)
                .orElseThrow(() -> new NotFoundException("Space " + spaceId + " does not exist."));
        if (space.getOwnerId() != ownerId) {
            throw new ForbiddenException("That space belongs to another user.");
        }
        return space;
    }

    public List<SpaceComponent> findRoots(long ownerId) {
        return spaces.findByOwnerIdAndParentIsNullOrderById(ownerId);
    }

    public List<SpaceComponent> findAll(long ownerId) {
        return spaces.findByOwnerId(ownerId);
    }

    public void delete(SpaceComponent space) {
        spaces.delete(space);
    }
}
