package com.plantorganizer.memory;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;

/** One thing the agent has learned about the user: a liked or disliked plant, or a stated preference. */
@Entity
public class UserPreference {

    public enum Kind { LIKE, DISLIKE, NOTE }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private long ownerId;
    @Enumerated(EnumType.STRING)
    private Kind kind;
    @Column(length = 500)
    private String subject;
    private LocalDateTime createdAt;

    protected UserPreference() {
    }

    public UserPreference(long ownerId, Kind kind, String subject, LocalDateTime createdAt) {
        this.ownerId = ownerId;
        this.kind = kind;
        this.subject = subject.length() > 500 ? subject.substring(0, 500) : subject;
        this.createdAt = createdAt;
    }

    public Long getId() {
        return id;
    }

    public long getOwnerId() {
        return ownerId;
    }

    public Kind getKind() {
        return kind;
    }

    public String getSubject() {
        return subject;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}
