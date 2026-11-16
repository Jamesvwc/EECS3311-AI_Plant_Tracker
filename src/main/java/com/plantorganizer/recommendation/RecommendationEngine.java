package com.plantorganizer.recommendation;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.plantorganizer.common.ExternalServiceException;
import com.plantorganizer.common.ForbiddenException;
import com.plantorganizer.common.InvalidInputException;
import com.plantorganizer.common.NotFoundException;
import com.plantorganizer.memory.AgentMemory;
import com.plantorganizer.memory.UserPreference;
import com.plantorganizer.plants.OwnedPlant;
import com.plantorganizer.plants.PlantRepository;
import com.plantorganizer.schedule.ScheduleManager;
import com.plantorganizer.spaces.SpaceManager;
import com.plantorganizer.user.User;
import com.plantorganizer.user.UserRepository;

/**
 * Stage 1 RecommendationEngine (F06/F07), the context of the recommendation
 * Strategy pattern. It builds a {@link RecommendationContext}, lets the
 * current strategy produce candidates, stores the list, and records the
 * user's reactions in {@link AgentMemory} so later recommendations improve.
 */
@Service
public class RecommendationEngine {

    public enum SortBy { CARE, NAME, SPACE }

    private static final Logger log = LoggerFactory.getLogger(RecommendationEngine.class);
    static final int LIST_SIZE = 6;

    private final LearnedPreferenceStrategy learnedStrategy;
    private final UserConstraintStrategy constraintStrategy;
    private final RecommendedPlantJpaRepository repository;
    private final PlantRepository plantRepository;
    private final SpaceManager spaceManager;
    private final ScheduleManager scheduleManager;
    private final AgentMemory memory;
    private final UserRepository userRepository;
    private final Clock clock;
    private RecommendationStrategy strategy;

    public RecommendationEngine(LearnedPreferenceStrategy learnedStrategy, UserConstraintStrategy constraintStrategy,
                                RecommendedPlantJpaRepository repository, PlantRepository plantRepository,
                                SpaceManager spaceManager, ScheduleManager scheduleManager, AgentMemory memory,
                                UserRepository userRepository, Clock clock) {
        this.learnedStrategy = learnedStrategy;
        this.constraintStrategy = constraintStrategy;
        this.repository = repository;
        this.plantRepository = plantRepository;
        this.spaceManager = spaceManager;
        this.scheduleManager = scheduleManager;
        this.memory = memory;
        this.userRepository = userRepository;
        this.clock = clock;
        this.strategy = learnedStrategy;
    }

    public void setStrategy(RecommendationStrategy strategy) {
        this.strategy = strategy;
    }

    /** F06: rebuilds the automatic list from learned preferences (keeps items the user accepted from F07). */
    @Transactional
    public synchronized List<RecommendedPlant> refresh(long userId) {
        setStrategy(learnedStrategy);
        List<RecommendedPlant> fresh = strategy.recommend(userId, buildContext(userId, null, true));
        repository.findByOwnerIdAndStatus(userId, RecommendedPlant.Status.ACTIVE).stream()
                .filter(r -> r.getSource() == RecommendedPlant.Source.LEARNED)
                .forEach(repository::delete);
        LocalDateTime now = LocalDateTime.now(clock);
        fresh.forEach(r -> r.place(userId, RecommendedPlant.Source.LEARNED, RecommendedPlant.Status.ACTIVE, null, now));
        repository.saveAll(fresh);
        User user = userRepository.findById(userId);
        user.setRecommendationsStale(false);
        userRepository.save(user);
        return list(userId, SortBy.CARE);
    }

    /** F07: suggestions for the user's stated constraints; they join the list only when accepted. */
    @Transactional
    public synchronized List<RecommendedPlant> recommendFor(long userId, String constraints) {
        if (constraints == null || constraints.isBlank()) {
            throw new InvalidInputException("Tell the agent what kind of plant you're looking for.");
        }
        setStrategy(constraintStrategy);
        List<RecommendedPlant> suggestions = strategy.recommend(userId, buildContext(userId, constraints.trim(), false));
        repository.findByOwnerIdAndStatus(userId, RecommendedPlant.Status.SUGGESTED).forEach(repository::delete);
        LocalDateTime now = LocalDateTime.now(clock);
        suggestions.forEach(r -> r.place(userId, RecommendedPlant.Source.REQUESTED, RecommendedPlant.Status.SUGGESTED,
                constraints.trim(), now));
        return repository.saveAll(suggestions);
    }

    @Transactional(readOnly = true)
    public List<RecommendedPlant> list(long userId, SortBy sortBy) {
        return sort(repository.findByOwnerIdAndStatus(userId, RecommendedPlant.Status.ACTIVE), sortBy);
    }

    /** Stage 1 sort(by): ease of care, name, or recommended space. */
    public static List<RecommendedPlant> sort(List<RecommendedPlant> plants, SortBy sortBy) {
        Comparator<RecommendedPlant> byName = Comparator.comparing(r -> r.getDisplayName().toLowerCase(Locale.ROOT));
        Comparator<RecommendedPlant> order = switch (sortBy == null ? SortBy.CARE : sortBy) {
            case CARE -> Comparator.comparingInt(RecommendedPlant::getCareRank).thenComparing(byName);
            case NAME -> byName;
            case SPACE -> Comparator.comparing((RecommendedPlant r) -> r.getRecommendedSpace() == null ? "￿"
                    : r.getRecommendedSpace().toLowerCase(Locale.ROOT)).thenComparing(byName);
        };
        List<RecommendedPlant> sorted = new ArrayList<>(plants);
        sorted.sort(order);
        return sorted;
    }

    /** F07: the user liked a suggestion, so it joins the list and the like is remembered. */
    @Transactional
    public RecommendedPlant accept(long userId, long id) {
        RecommendedPlant plant = owned(userId, id);
        plant.accept();
        memory.savePreference(userId, UserPreference.Kind.LIKE, plant.getDisplayName() + " (" + plant.getScientificName() + ")");
        return repository.save(plant);
    }

    /** F06/F07: removes a recommendation and remembers the dislike so it is not suggested again. */
    @Transactional
    public void unrecommend(long userId, long id) {
        RecommendedPlant plant = owned(userId, id);
        memory.savePreference(userId, UserPreference.Kind.DISLIKE, plant.getDisplayName() + " (" + plant.getScientificName() + ")");
        repository.delete(plant);
    }

    @Transactional(readOnly = true)
    public RecommendedPlant find(long userId, long id) {
        return owned(userId, id);
    }

    /** Called when the owned list changes, so the next visit refreshes the list (Stage 1 trigger). */
    @Transactional
    public void markStale(long userId) {
        User user = userRepository.findById(userId);
        user.setRecommendationsStale(true);
        userRepository.save(user);
    }

    /** Daily timer (Stage 1 "regularly refreshed"): refreshes stale lists for users with plants. */
    @Scheduled(cron = "0 30 3 * * *")
    public void refreshStaleLists() {
        for (User user : userRepository.findAll()) {
            if (user.isRecommendationsStale() && !plantRepository.findAll(user.getId()).isEmpty()) {
                try {
                    refresh(user.getId());
                } catch (ExternalServiceException e) {
                    log.info("Skipped recommendation refresh for user {}: {}", user.getId(), e.getMessage());
                }
            }
        }
    }

    private RecommendedPlant owned(long userId, long id) {
        RecommendedPlant plant = repository.findById(id)
                .orElseThrow(() -> new NotFoundException("Recommendation " + id + " does not exist."));
        if (plant.getOwnerId() != userId) {
            throw new ForbiddenException("That recommendation belongs to another user.");
        }
        return plant;
    }

    RecommendationContext buildContext(long userId, String constraints, boolean excludeListed) {
        List<OwnedPlant> owned = plantRepository.findAll(userId);
        List<String> ownedDescriptions = owned.stream()
                .map(p -> p.getDisplayName() + " (" + p.getScientificName() + ", care: "
                        + (p.getCare().getCareLevel() != null ? p.getCare().getCareLevel() : "unknown")
                        + (p.getWateringIntervalDays() != null ? ", water every " + p.getWateringIntervalDays() + " days" : "")
                        + ")")
                .toList();
        List<String> exclude = new ArrayList<>();
        owned.forEach(p -> exclude.add(p.getScientificName()));
        plantRepository.findDesired(userId).forEach(d -> exclude.add(d.getScientificName()));
        if (excludeListed) {
            repository.findByOwnerIdAndStatus(userId, RecommendedPlant.Status.ACTIVE).stream()
                    .filter(r -> r.getSource() == RecommendedPlant.Source.REQUESTED)
                    .forEach(r -> exclude.add(r.getScientificName()));
        } else {
            repository.findByOwnerIdAndStatus(userId, RecommendedPlant.Status.ACTIVE)
                    .forEach(r -> exclude.add(r.getScientificName()));
        }
        return new RecommendationContext(ownedDescriptions, spaceManager.describeSpaces(userId),
                wateringHabit(userId, owned), memory.getPreferences(userId), constraints, exclude, LIST_SIZE);
    }

    private String wateringHabit(long userId, List<OwnedPlant> owned) {
        String availability = scheduleManager.availabilityFor(userId).summary();
        double avgInterval = owned.stream().filter(p -> p.getWateringIntervalDays() != null)
                .mapToInt(OwnedPlant::getWateringIntervalDays).average().orElse(0);
        String routine = avgInterval > 0
                ? "currently waters " + owned.size() + " plant(s), on average every " + Math.round(avgInterval) + " days"
                : "no watering routine yet";
        return routine + ". Availability: " + (availability != null ? availability : "unknown") + ".";
    }
}
