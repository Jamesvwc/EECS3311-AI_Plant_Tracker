package com.plantorganizer.plants;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface PlantPhotoJpaRepository extends JpaRepository<PlantPhoto, Long> {

    List<PlantPhoto> findByPlantIdOrderByUploadedAtDesc(long plantId);

    void deleteByPlantId(long plantId);
}
