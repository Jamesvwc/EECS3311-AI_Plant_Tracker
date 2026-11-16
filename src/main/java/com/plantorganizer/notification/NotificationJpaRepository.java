package com.plantorganizer.notification;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface NotificationJpaRepository extends JpaRepository<Notification, Long> {

    List<Notification> findTop50ByOwnerIdOrderByCreatedAtDesc(long ownerId);

    List<Notification> findByOwnerIdAndReadFlagFalse(long ownerId);

    List<Notification> findByPlantIdInAndReadFlagFalse(Collection<Long> plantIds);

    long countByOwnerIdAndReadFlagFalse(long ownerId);

    void deleteByPlantId(long plantId);
}
