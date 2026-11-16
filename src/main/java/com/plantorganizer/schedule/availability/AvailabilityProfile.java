package com.plantorganizer.schedule.availability;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;

/** The user's latest availability (F03), stored as JSON so the next schedule generation (F02) can use it. */
@Entity
public class AvailabilityProfile {

    @Id
    private Long ownerId;
    @Column(length = 20000)
    private String json;
    private LocalDateTime updatedAt;

    protected AvailabilityProfile() {
    }

    public AvailabilityProfile(long ownerId, String json, LocalDateTime updatedAt) {
        this.ownerId = ownerId;
        this.json = json;
        this.updatedAt = updatedAt;
    }

    public Long getOwnerId() {
        return ownerId;
    }

    public String getJson() {
        return json;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }
}
