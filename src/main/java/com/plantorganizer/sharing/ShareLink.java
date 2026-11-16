package com.plantorganizer.sharing;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;

/** F12: a secret token plus the plants the owner chose to share with someone who has no account. */
@Entity
public class ShareLink {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String token;

    private long ownerId;
    private String label;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "share_link_plant", joinColumns = @JoinColumn(name = "share_link_id"))
    @Column(name = "plant_id")
    private List<Long> plantIds = new ArrayList<>();

    private Instant createdAt;
    private Instant expiresAt;

    protected ShareLink() {
    }

    public ShareLink(String token, long ownerId, String label, List<Long> plantIds, Instant createdAt, Instant expiresAt) {
        this.token = token;
        this.ownerId = ownerId;
        this.label = label;
        this.plantIds = new ArrayList<>(plantIds);
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
    }

    public boolean isExpired(Instant now) {
        return expiresAt != null && now.isAfter(expiresAt);
    }

    public Long getId() {
        return id;
    }

    public String getToken() {
        return token;
    }

    public long getOwnerId() {
        return ownerId;
    }

    public String getLabel() {
        return label;
    }

    public List<Long> getPlantIds() {
        return List.copyOf(plantIds);
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }
}
