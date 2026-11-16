package com.plantorganizer.plants;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface OwnedPlantJpaRepository extends JpaRepository<OwnedPlant, Long> {

    List<OwnedPlant> findByOwnerIdOrderByAddedAtDesc(long ownerId);

    List<OwnedPlant> findByIdIn(Collection<Long> ids);
}
