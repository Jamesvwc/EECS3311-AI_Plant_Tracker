package com.plantorganizer.plants;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;

/** A plant the user would like to buy or learn about (F09), optionally with the space they would put it in. */
@Entity
public class DesiredPlant {

    public enum FitVerdict { GOOD, FAIR, POOR, UNKNOWN }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private long ownerId;
    private String scientificName;
    private String commonName;
    private Long intendedSpaceId;
    @Column(length = 1000)
    private String note;
    @Column(length = 1000)
    private String imageUrl;
    @Enumerated(EnumType.STRING)
    private FitVerdict fitVerdict;
    @Column(length = 3000)
    private String fitExplanation;
    private LocalDateTime fitCheckedAt;
    private LocalDateTime addedAt;

    protected DesiredPlant() {
    }

    public DesiredPlant(long ownerId, String scientificName, String commonName, Long intendedSpaceId, String note,
                        String imageUrl, LocalDateTime addedAt) {
        this.ownerId = ownerId;
        this.scientificName = scientificName;
        this.commonName = commonName;
        this.intendedSpaceId = intendedSpaceId;
        this.note = note;
        this.imageUrl = imageUrl;
        this.addedAt = addedAt;
    }

    public void recordFit(FitVerdict verdict, String explanation, LocalDateTime at) {
        this.fitVerdict = verdict;
        this.fitExplanation = explanation != null && explanation.length() > 3000 ? explanation.substring(0, 3000) : explanation;
        this.fitCheckedAt = at;
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

    public Long getIntendedSpaceId() {
        return intendedSpaceId;
    }

    public void setIntendedSpaceId(Long intendedSpaceId) {
        if (!java.util.Objects.equals(this.intendedSpaceId, intendedSpaceId)) {
            this.fitVerdict = null;
            this.fitExplanation = null;
            this.fitCheckedAt = null;
        }
        this.intendedSpaceId = intendedSpaceId;
    }

    public String getNote() {
        return note;
    }

    public void setNote(String note) {
        this.note = note;
    }

    public String getImageUrl() {
        return imageUrl;
    }

    public void setImageUrl(String imageUrl) {
        this.imageUrl = imageUrl;
    }

    public FitVerdict getFitVerdict() {
        return fitVerdict;
    }

    public String getFitExplanation() {
        return fitExplanation;
    }

    public LocalDateTime getFitCheckedAt() {
        return fitCheckedAt;
    }

    public LocalDateTime getAddedAt() {
        return addedAt;
    }
}
