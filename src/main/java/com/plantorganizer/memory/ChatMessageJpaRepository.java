package com.plantorganizer.memory;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ChatMessageJpaRepository extends JpaRepository<ChatMessage, Long> {

    List<ChatMessage> findTop12ByOwnerIdOrderByCreatedAtDescIdDesc(long ownerId);

    List<ChatMessage> findTop100ByOwnerIdOrderByCreatedAtDescIdDesc(long ownerId);

    void deleteByOwnerId(long ownerId);
}
