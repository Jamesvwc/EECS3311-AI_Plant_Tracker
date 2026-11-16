package com.plantorganizer.sharing;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.plantorganizer.common.ForbiddenException;
import com.plantorganizer.common.InvalidInputException;
import com.plantorganizer.common.NotFoundException;
import com.plantorganizer.plants.OwnedPlant;
import com.plantorganizer.plants.PlantRepository;
import com.plantorganizer.schedule.CalendarEntry;
import com.plantorganizer.schedule.ScheduleManager;
import com.plantorganizer.user.User;
import com.plantorganizer.user.UserRepository;

/**
 * Stage 1 ShareService (F11, F12). Fully deterministic: it manages
 * friendships, enforces who may see what, and creates share links for people
 * without an account.
 */
@Service
@Transactional
public class ShareService {

    static final Duration LINK_LIFETIME = Duration.ofDays(30);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final UserRepository userRepository;
    private final PlantRepository plantRepository;
    private final ScheduleManager scheduleManager;
    private final Clock clock;

    public ShareService(UserRepository userRepository, PlantRepository plantRepository,
                        ScheduleManager scheduleManager, Clock clock) {
        this.userRepository = userRepository;
        this.plantRepository = plantRepository;
        this.scheduleManager = scheduleManager;
        this.clock = clock;
    }

    // ---------------------------------------------------------------- F11 friends

    /** Links two users as friends (both directions). Neither can see the other's plants until allowed. */
    public FriendView addFriend(long userId, long friendId) {
        if (userId == friendId) {
            throw new InvalidInputException("You can't add yourself as a friend.");
        }
        User me = userRepository.findById(userId);
        User friend = userRepository.findById(friendId);
        me.addFriend(friendId);
        friend.addFriend(userId);
        userRepository.save(me);
        userRepository.save(friend);
        return view(me, friend);
    }

    public void removeFriend(long userId, long friendId) {
        User me = userRepository.findById(userId);
        User friend = userRepository.findById(friendId);
        me.removeFriend(friendId);
        friend.removeFriend(userId);
        userRepository.save(me);
        userRepository.save(friend);
    }

    /** The owner decides whether a friend may see their plant list. */
    public FriendView setViewerAllowed(long userId, long friendId, boolean allowed) {
        User me = userRepository.findById(userId);
        if (!me.getFriendIds().contains(friendId)) {
            throw new InvalidInputException("Add this person as a friend first.");
        }
        me.setViewerAllowed(friendId, allowed);
        userRepository.save(me);
        return view(me, userRepository.findById(friendId));
    }

    @Transactional(readOnly = true)
    public List<FriendView> listFriends(long userId) {
        User me = userRepository.findById(userId);
        return me.getFriendIds().stream().sorted()
                .map(userRepository::findById)
                .map(friend -> view(me, friend))
                .toList();
    }

    /** UC11: names only, and only when the owner allows this viewer. */
    @Transactional(readOnly = true)
    public List<PlantSummary> viewFriendPlants(long viewerId, long ownerId) {
        User owner = userRepository.findById(ownerId);
        if (!owner.canBeViewedBy(viewerId)) {
            throw new ForbiddenException(owner.getDisplayName() + " hasn't shared their plants with you.");
        }
        return plantRepository.findAll(ownerId).stream()
                .map(p -> new PlantSummary(p.getDisplayName(), p.getScientificName()))
                .toList();
    }

    private static FriendView view(User me, User friend) {
        return new FriendView(friend.getId(), friend.getDisplayName(), me.getAllowedViewerIds().contains(friend.getId()),
                friend.canBeViewedBy(me.getId()));
    }

    // ---------------------------------------------------------------- F12 share links

    public ShareLinkView createShareLink(long userId, List<Long> plantIds, String label) {
        if (plantIds == null || plantIds.isEmpty()) {
            throw new InvalidInputException("Choose at least one plant to share.");
        }
        List<OwnedPlant> plants = plantIds.stream().map(id -> plantRepository.findOwned(userId, id)).toList();
        byte[] bytes = new byte[24];
        RANDOM.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        var now = clock.instant();
        ShareLink link = userRepository.saveShareLink(new ShareLink(token, userId,
                label == null || label.isBlank() ? "Plant care instructions" : label.trim(),
                plants.stream().map(OwnedPlant::getId).toList(), now, now.plus(LINK_LIFETIME)));
        return linkView(link, plants);
    }

    @Transactional(readOnly = true)
    public List<ShareLinkView> listShareLinks(long userId) {
        List<ShareLinkView> views = new ArrayList<>();
        for (ShareLink link : userRepository.findShareLinks(userId)) {
            views.add(linkView(link, plantRepository.findByIds(link.getPlantIds())));
        }
        return views;
    }

    public void revokeShareLink(long userId, String token) {
        ShareLink link = userRepository.findShareLink(token)
                .orElseThrow(() -> new NotFoundException("That share link does not exist."));
        if (link.getOwnerId() != userId) {
            throw new ForbiddenException("That share link belongs to another user.");
        }
        userRepository.deleteShareLink(link);
    }

    /** Opened by a guest without an account; an unknown or expired token is an error. */
    @Transactional(readOnly = true)
    public SharedScheduleView openShareLink(String token) {
        ShareLink link = userRepository.findShareLink(token)
                .filter(l -> !l.isExpired(clock.instant()))
                .orElseThrow(() -> new NotFoundException("This share link is invalid or has expired."));
        User owner = userRepository.findById(link.getOwnerId());
        Map<Long, OwnedPlant> plants = plantRepository.findByIds(link.getPlantIds()).stream()
                .collect(Collectors.toMap(OwnedPlant::getId, Function.identity()));
        LocalDateTime now = LocalDateTime.now(clock);
        List<SharedScheduleView.SharedPlant> shared = new ArrayList<>();
        for (Long id : link.getPlantIds()) {
            OwnedPlant p = plants.get(id);
            if (p == null) {
                continue; // deleted since the link was made
            }
            List<LocalDateTime> next = scheduleManager.plannedBetween(p.getOwnerId(), now.minusDays(2), now.plusDays(21))
                    .stream().filter(e -> e.getPlantId() == id).map(CalendarEntry::getDueAt).limit(6).toList();
            shared.add(new SharedScheduleView.SharedPlant(p.getDisplayName(), p.getScientificName(),
                    p.getCare().getWatering(), p.getCare().getWateringFrequency(), p.getWateringIntervalDays(),
                    p.getCare().sunlightList(), next));
        }
        return new SharedScheduleView(owner.getDisplayName(), link.getLabel(), link.getExpiresAt(), shared);
    }

    private static ShareLinkView linkView(ShareLink link, List<OwnedPlant> plants) {
        return new ShareLinkView(link.getToken(), "share.html?token=" + link.getToken(), link.getLabel(),
                plants.stream().map(OwnedPlant::getDisplayName).toList(), link.getCreatedAt(), link.getExpiresAt());
    }
}
