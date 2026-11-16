package com.plantorganizer.memory;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface UserPreferenceJpaRepository extends JpaRepository<UserPreference, Long> {

    List<UserPreference> findByOwnerIdOrderByCreatedAtDesc(long ownerId);
}
