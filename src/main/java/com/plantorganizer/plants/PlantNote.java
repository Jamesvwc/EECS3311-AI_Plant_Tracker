package com.plantorganizer.plants;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;

/** A dated note on an owned plant, written by the user, the agent, or saved from a health check (F05/F08). */
@Entity
public class PlantNote {

    public enum Kind { USER, AGENT, HEALTH }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private long plantId;
    @Enumerated(EnumType.STRING)
    private Kind kind;
    @Column(length = 8000)
    private String text;
    private LocalDateTime createdAt;

    protected PlantNote() {
    }

    public PlantNote(long plantId, Kind kind, String text, LocalDateTime createdAt) {
        this.plantId = plantId;
        this.kind = kind;
        this.text = text.length() > 8000 ? text.substring(0, 8000) : text;
        this.createdAt = createdAt;
    }

    public Long getId() {
        return id;
    }

    public long getPlantId() {
        return plantId;
    }

    public Kind getKind() {
        return kind;
    }

    public String getText() {
        return text;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}
