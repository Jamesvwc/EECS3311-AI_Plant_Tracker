package com.plantorganizer.recommendation;

import java.time.LocalDateTime;
import java.util.Locale;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;

/** One recommendation with the reason the agent gave (F06/F07). */
@Entity
public class RecommendedPlant {

    /** LEARNED: made automatically from what the agent learned (F06). REQUESTED: from the user's constraints (F07). */
    public enum Source { LEARNED, REQUESTED }

    /** ACTIVE: in the recommendations list. SUGGESTED: an F07 answer the user has not accepted yet. */
    public enum Status { ACTIVE, SUGGESTED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private long ownerId;
    private String scientificName;
    private String commonName;
    @Column(length = 1500)
    private String reason;
    private String careLevel;
    private String recommendedSpace;
    private String sunlight;
    private String watering;
    @Column(length = 1000)
    private String imageUrl;
    /** True when the species was found in the Perenual database (not only proposed by the AI). */
    private boolean verified;
    private Boolean petSafe;
    @Enumerated(EnumType.STRING)
    private Source source;
    @Enumerated(EnumType.STRING)
    private Status status;
    @Column(length = 500)
    private String request;
    private LocalDateTime createdAt;

    protected RecommendedPlant() {
    }

    public RecommendedPlant(long ownerId, String scientificName, String commonName, String reason, String careLevel,
                            String recommendedSpace, String sunlight, String watering, Boolean petSafe) {
        this.ownerId = ownerId;
        this.scientificName = scientificName;
        this.commonName = commonName;
        this.reason = reason != null && reason.length() > 1500 ? reason.substring(0, 1500) : reason;
        this.careLevel = careLevel;
        this.recommendedSpace = recommendedSpace;
        this.sunlight = sunlight;
        this.watering = watering;
        this.petSafe = petSafe;
    }

    /** 1 = easy, 2 = medium, 3 = hard; used for "sort by ease of care". */
    public int getCareRank() {
        String c = careLevel == null ? "" : careLevel.toLowerCase(Locale.ROOT);
        if (c.contains("easy") || c.contains("low")) {
            return 1;
        }
        if (c.contains("hard") || c.contains("high") || c.contains("difficult")) {
            return 3;
        }
        return 2;
    }

    void markVerified(String imageUrl) {
        this.verified = true;
        if (this.imageUrl == null) {
            this.imageUrl = imageUrl;
        }
    }

    void place(long ownerId, Source source, Status status, String request, LocalDateTime createdAt) {
        this.ownerId = ownerId;
        this.source = source;
        this.status = status;
        this.request = request;
        this.createdAt = createdAt;
    }

    void accept() {
        this.status = Status.ACTIVE;
    }

    public String getDisplayName() {
        return commonName != null ? commonName : scientificName;
    }

    public Long getId() {
        return id;
    }

    public long getOwnerId() {
        return ownerId;
    }

    public String getScientificName() {
        return scientificName;
    }

    public String getCommonName() {
        return commonName;
    }

    public String getReason() {
        return reason;
    }

    public String getCareLevel() {
        return careLevel;
    }

    public String getRecommendedSpace() {
        return recommendedSpace;
    }

    public String getSunlight() {
        return sunlight;
    }

    public String getWatering() {
        return watering;
    }

    public String getImageUrl() {
        return imageUrl;
    }

    public boolean isVerified() {
        return verified;
    }

    public Boolean getPetSafe() {
        return petSafe;
    }

    public Source getSource() {
        return source;
    }

    public Status getStatus() {
        return status;
    }

    public String getRequest() {
        return request;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}
