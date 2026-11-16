package com.plantorganizer.notification;

import java.time.LocalDateTime;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;

/** A watering reminder shown under the bell icon (F04). */
@Entity
public class Notification {

    public enum Type { DUE, MISSED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private long ownerId;
    private long plantId;
    @Enumerated(EnumType.STRING)
    private Type type;
    private String message;
    private LocalDateTime createdAt;
    private boolean readFlag;

    protected Notification() {
    }

    public Notification(long ownerId, long plantId, Type type, String message, LocalDateTime createdAt) {
        this.ownerId = ownerId;
        this.plantId = plantId;
        this.type = type;
        this.message = message;
        this.createdAt = createdAt;
    }

    public void markRead() {
        this.readFlag = true;
    }

    public Long getId() {
        return id;
    }

    public long getOwnerId() {
        return ownerId;
    }

    public long getPlantId() {
        return plantId;
    }

    public Type getType() {
        return type;
    }

    public String getMessage() {
        return message;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public boolean isRead() {
        return readFlag;
    }
}
