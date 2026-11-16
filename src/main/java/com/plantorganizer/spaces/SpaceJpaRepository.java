package com.plantorganizer.spaces;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface SpaceJpaRepository extends JpaRepository<SpaceComponent, Long> {

    List<SpaceComponent> findByOwnerIdAndParentIsNullOrderById(long ownerId);

    List<SpaceComponent> findByOwnerId(long ownerId);
}
