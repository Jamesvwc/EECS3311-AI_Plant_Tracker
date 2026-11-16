package com.plantorganizer.plants;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface HealthRecordJpaRepository extends JpaRepository<HealthRecord, Long> {

    List<HealthRecord> findByPlantIdOrderByCreatedAtDesc(long plantId);

    void deleteByPlantId(long plantId);
}
