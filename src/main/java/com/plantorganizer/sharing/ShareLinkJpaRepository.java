package com.plantorganizer.sharing;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ShareLinkJpaRepository extends JpaRepository<ShareLink, Long> {

    Optional<ShareLink> findByToken(String token);

    List<ShareLink> findByOwnerIdOrderByCreatedAtDesc(long ownerId);
}
