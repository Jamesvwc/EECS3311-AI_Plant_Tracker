package com.plantorganizer.plants;

import java.time.Duration;
import java.time.LocalDateTime;

import com.plantorganizer.spaces.Spot;

import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;

/**
 * A plant the user owns (F08), with its care notes, where it lives (F10) and
 * its watering status (F04). It is the context of the State pattern: the
 * stored {@link WateringStatus} selects the {@link WateringState} object that
 * handles {@link #checkDue} and {@link #markWatered}.
 */
@Entity
public class OwnedPlant {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private long ownerId;
    private String nickname;
    private String scientificName;
    private String commonName;

    @Embedded
    private CareNotes care = new CareNotes();

    /** Days between waterings used by the schedule (F02), and how sure we are of it. */
    private Integer wateringIntervalDays;
    @Enumerated(EnumType.STRING)
    private IntervalQuality intervalQuality;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "spot_id")
    private Spot spot;

    @Enumerated(EnumType.STRING)
    private WateringStatus wateringStatus = WateringStatus.OK;
    private LocalDateTime lastWateredAt;
    private boolean notificationsEnabled;
    private LocalDateTime addedAt;

    protected OwnedPlant() {
    }

    public OwnedPlant(long ownerId, String scientificName, String commonName, String nickname, CareNotes care,
                      LocalDateTime addedAt) {
        this.ownerId = ownerId;
        this.scientificName = scientificName;
        this.commonName = commonName;
        this.nickname = nickname == null || nickname.isBlank() ? null : nickname.trim();
        this.care = care == null ? new CareNotes() : care;
        this.addedAt = addedAt;
    }

    // ---- State pattern: the context delegates to the current state ----

    public WateringStatus checkDue(LocalDateTime now, LocalDateTime nextDue, Duration overdueAfter) {
        wateringStatus.state().onTick(this, now, nextDue, overdueAfter);
        return wateringStatus;
    }

    public String markWatered(LocalDateTime when) {
        return wateringStatus.state().onWatered(this, when);
    }

    void setState(WateringState state) {
        this.wateringStatus = state.status();
    }

    void recordWatering(LocalDateTime when) {
        this.lastWateredAt = when;
    }

    // ---- accessors ----

    public String getDisplayName() {
        if (nickname != null) {
            return nickname;
        }
        return commonName != null ? commonName : scientificName;
    }

    public Long getId() {
        return id;
    }

    public long getOwnerId() {
        return ownerId;
    }

    public String getNickname() {
        return nickname;
    }

    public void setNickname(String nickname) {
        this.nickname = nickname == null || nickname.isBlank() ? null : nickname.trim();
    }

    public String getScientificName() {
        return scientificName;
    }

    public String getCommonName() {
        return commonName;
    }

    /**
     * Never null. JPA loads an embedded value whose columns are all null as
     * null, which happens when a plant was added while no care source could
     * be reached (UC02: its notes stay empty).
     */
    public CareNotes getCare() {
        if (care == null) {
            care = new CareNotes();
        }
        return care;
    }

    public Integer getWateringIntervalDays() {
        return wateringIntervalDays;
    }

    public IntervalQuality getIntervalQuality() {
        return intervalQuality;
    }

    public void setWateringInterval(int days, IntervalQuality quality) {
        this.wateringIntervalDays = days;
        this.intervalQuality = quality;
    }

    public Spot getSpot() {
        return spot;
    }

    public void setSpot(Spot spot) {
        this.spot = spot;
    }

    public WateringStatus getWateringStatus() {
        return wateringStatus;
    }

    public LocalDateTime getLastWateredAt() {
        return lastWateredAt;
    }

    public boolean isNotificationsEnabled() {
        return notificationsEnabled;
    }

    public void setNotificationsEnabled(boolean notificationsEnabled) {
        this.notificationsEnabled = notificationsEnabled;
    }

    public LocalDateTime getAddedAt() {
        return addedAt;
    }
}
