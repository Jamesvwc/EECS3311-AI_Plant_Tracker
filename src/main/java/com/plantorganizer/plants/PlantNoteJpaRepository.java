package com.plantorganizer.plants;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface PlantNoteJpaRepository extends JpaRepository<PlantNote, Long> {

    List<PlantNote> findByPlantIdOrderByCreatedAtDesc(long plantId);

    void deleteByPlantId(long plantId);
}
