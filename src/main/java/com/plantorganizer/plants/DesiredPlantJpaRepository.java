package com.plantorganizer.plants;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface DesiredPlantJpaRepository extends JpaRepository<DesiredPlant, Long> {

    List<DesiredPlant> findByOwnerIdOrderByAddedAtDesc(long ownerId);
}
