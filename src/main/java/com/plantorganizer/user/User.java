package com.plantorganizer.user;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;

/**
 * A person using PlantOrganizer (Stage 1 class User). Holds the friend list and
 * the "allowed viewers" list used by F11, plus a few per-user settings.
 * Stored in table app_user because "user" is a reserved word in SQL.
 */
@Entity
@Table(name = "app_user")
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String displayName;

    private Instant createdAt;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "user_friend", joinColumns = @JoinColumn(name = "user_id"))
    @Column(name = "friend_id")
    private Set<Long> friendIds = new HashSet<>();

    /** Friends who may see this user's plant list (F11). */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "user_allowed_viewer", joinColumns = @JoinColumn(name = "user_id"))
    @Column(name = "viewer_id")
    private Set<Long> allowedViewerIds = new HashSet<>();

    /** F04: the agent asks about reminders only the first time a schedule is made. */
    private boolean notificationsAsked;
    /** F04: the answer to that question, applied to plants added later. */
    private boolean notifyByDefault;
    /** F06: set when the owned list changes so recommendations are refreshed. */
    private boolean recommendationsStale = true;

    protected User() {
    }

    public User(String displayName, Instant createdAt) {
        this.displayName = displayName;
        this.createdAt = createdAt;
    }

    public void addFriend(long friendId) {
        friendIds.add(friendId);
    }

    public void removeFriend(long friendId) {
        friendIds.remove(friendId);
        allowedViewerIds.remove(friendId);
    }

    public void setViewerAllowed(long friendId, boolean allowed) {
        if (allowed) {
            allowedViewerIds.add(friendId);
        } else {
            allowedViewerIds.remove(friendId);
        }
    }

    /** F11 rule: only friends that the owner has allowed may see the owner's plants. */
    public boolean canBeViewedBy(long viewerId) {
        return friendIds.contains(viewerId) && allowedViewerIds.contains(viewerId);
    }

    public Long getId() {
        return id;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Set<Long> getFriendIds() {
        return Set.copyOf(friendIds);
    }

    public Set<Long> getAllowedViewerIds() {
        return Set.copyOf(allowedViewerIds);
    }

    public boolean isNotificationsAsked() {
        return notificationsAsked;
    }

    public void setNotificationsAsked(boolean notificationsAsked) {
        this.notificationsAsked = notificationsAsked;
    }

    public boolean isNotifyByDefault() {
        return notifyByDefault;
    }

    public void setNotifyByDefault(boolean notifyByDefault) {
        this.notifyByDefault = notifyByDefault;
    }

    public boolean isRecommendationsStale() {
        return recommendationsStale;
    }

    public void setRecommendationsStale(boolean recommendationsStale) {
        this.recommendationsStale = recommendationsStale;
    }
}
