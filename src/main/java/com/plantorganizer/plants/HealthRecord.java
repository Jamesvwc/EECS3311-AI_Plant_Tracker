package com.plantorganizer.plants;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;

/** One health check of an owned plant (F05): the AI assessment and how it compares with the previous check. */
@Entity
public class HealthRecord {

    public enum Trend { FIRST_CHECK, IMPROVING, STABLE, WORSENING }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private long plantId;
    private Long photoId;
    private LocalDateTime createdAt;
    @Column(length = 2000)
    private String userDescription;
    /** 1 (dying) to 10 (thriving). */
    private int healthScore;
    @Enumerated(EnumType.STRING)
    private HealthStatus status;
    @Column(length = 2000)
    private String summary;
    @Column(length = 4000)
    private String issues;
    @Column(length = 4000)
    private String careSteps;
    @Column(length = 2000)
    private String comparison;
    @Enumerated(EnumType.STRING)
    private Trend trend;
    @Column(length = 1000)
    private String speciesCheck;

    protected HealthRecord() {
    }

    public HealthRecord(long plantId, Long photoId, LocalDateTime createdAt, String userDescription, int healthScore,
                        HealthStatus status, String summary, String issues, String careSteps, String comparison,
                        String speciesCheck) {
        this.plantId = plantId;
        this.photoId = photoId;
        this.createdAt = createdAt;
        this.userDescription = cut(userDescription, 2000);
        this.healthScore = Math.max(1, Math.min(10, healthScore));
        this.status = status;
        this.summary = cut(summary, 2000);
        this.issues = cut(issues, 4000);
        this.careSteps = cut(careSteps, 4000);
        this.comparison = cut(comparison, 2000);
        this.speciesCheck = cut(speciesCheck, 1000);
        this.trend = Trend.FIRST_CHECK;
    }

    /**
     * Stage 1 HealthRecord.compareTo(other): is this check better or worse than
     * an earlier one? A change of 1 point or less counts as stable.
     */
    public Trend compareTo(HealthRecord earlier) {
        if (earlier == null) {
            return Trend.FIRST_CHECK;
        }
        int delta = healthScore - earlier.healthScore;
        if (delta >= 2) {
            return Trend.IMPROVING;
        }
        if (delta <= -2) {
            return Trend.WORSENING;
        }
        return Trend.STABLE;
    }

    public void setTrend(Trend trend) {
        this.trend = trend;
    }

    private static String cut(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }

    public Long getId() {
        return id;
    }

    public long getPlantId() {
        return plantId;
    }

    public Long getPhotoId() {
        return photoId;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public String getUserDescription() {
        return userDescription;
    }

    public int getHealthScore() {
        return healthScore;
    }

    public HealthStatus getStatus() {
        return status;
    }

    public String getSummary() {
        return summary;
    }

    public String getIssues() {
        return issues;
    }

    public String getCareSteps() {
        return careSteps;
    }

    public String getComparison() {
        return comparison;
    }

    public Trend getTrend() {
        return trend;
    }

    public String getSpeciesCheck() {
        return speciesCheck;
    }
}
