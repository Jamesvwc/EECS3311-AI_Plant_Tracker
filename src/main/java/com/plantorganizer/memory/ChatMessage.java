package com.plantorganizer.memory;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;

/** One message of the conversation with the agent in the chat box (CLI), kept as short-term memory. */
@Entity
public class ChatMessage {

    public enum Role { USER, AGENT }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private long ownerId;
    @Enumerated(EnumType.STRING)
    private Role role;
    @Column(length = 8000)
    private String text;
    private LocalDateTime createdAt;

    protected ChatMessage() {
    }

    public ChatMessage(long ownerId, Role role, String text, LocalDateTime createdAt) {
        this.ownerId = ownerId;
        this.role = role;
        this.text = text.length() > 8000 ? text.substring(0, 8000) : text;
        this.createdAt = createdAt;
    }

    public Long getId() {
        return id;
    }

    public Role getRole() {
        return role;
    }

    public String getText() {
        return text;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}
