package com.plantorganizer.recommendation;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface RecommendedPlantJpaRepository extends JpaRepository<RecommendedPlant, Long> {

    List<RecommendedPlant> findByOwnerIdAndStatus(long ownerId, RecommendedPlant.Status status);

    List<RecommendedPlant> findByOwnerId(long ownerId);
}
