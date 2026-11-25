package com.plantorganizer.schedule;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.plantorganizer.plants.IntervalQuality;
import com.plantorganizer.plants.OwnedPlant;
import com.plantorganizer.plants.PlantRepository;
import com.plantorganizer.schedule.CalendarEntry.EntrySpec;
import com.plantorganizer.schedule.availability.Availability;
import com.plantorganizer.schedule.availability.AvailabilityImporter;
import com.plantorganizer.schedule.availability.AvailabilityInput;
import com.plantorganizer.schedule.availability.AvailabilityProfile;
import com.plantorganizer.schedule.availability.AvailabilityProfileJpaRepository;
import com.plantorganizer.schedule.availability.ImporterFactory;
import com.plantorganizer.schedule.command.AddEntriesCommand;
import com.plantorganizer.schedule.command.CommandHistory;
import com.plantorganizer.schedule.command.RescheduleCommand;

import tools.jackson.databind.json.JsonMapper;

/**
 * Stage 1 ScheduleManager: owns the watering calendar (F02-F04). It plays
 * four pattern roles:
 *   - Strategy context: {@link #setStrategy} picks GroupedWateringStrategy or
 *     FallbackWateringStrategy for each plan;
 *   - Observer subject: {@link #registerObserver} / {@link #notifyObservers}
 *     keep NotificationService in sync with every calendar change;
 *   - Command receiver and client: it creates AddEntriesCommand /
 *     RescheduleCommand objects, runs them through CommandHistory, and
 *     {@link #replaceEntries} is the operation they call;
 *   - it asks ImporterFactory (Factory Method) for the right availability parser.
 */
@Service
public class ScheduleManager {

    private static final Logger log = LoggerFactory.getLogger(ScheduleManager.class);
    static final int HORIZON_DAYS = 56;
    private static final DateTimeFormatter SHORT = DateTimeFormatter.ofPattern("EEE MMM d, HH:mm", Locale.ENGLISH);

    private final CalendarEntryJpaRepository entries;
    private final PlantRepository plantRepository;
    private final AvailabilityProfileJpaRepository availabilityProfiles;
    private final ImporterFactory importerFactory;
    private final CommandHistory commandHistory;
    private final GroupedWateringStrategy groupedStrategy;
    private final FallbackWateringStrategy fallbackStrategy;
    private final JsonMapper jsonMapper;
    private final Clock clock;
    private final List<CalendarObserver> observers = new CopyOnWriteArrayList<>();
    private SchedulingStrategy strategy;

    public ScheduleManager(CalendarEntryJpaRepository entries, PlantRepository plantRepository,
                           AvailabilityProfileJpaRepository availabilityProfiles, ImporterFactory importerFactory,
                           CommandHistory commandHistory, GroupedWateringStrategy groupedStrategy,
                           FallbackWateringStrategy fallbackStrategy, JsonMapper jsonMapper, Clock clock) {
        this.entries = entries;
        this.plantRepository = plantRepository;
        this.availabilityProfiles = availabilityProfiles;
        this.importerFactory = importerFactory;
        this.commandHistory = commandHistory;
        this.groupedStrategy = groupedStrategy;
        this.fallbackStrategy = fallbackStrategy;
        this.jsonMapper = jsonMapper;
        this.clock = clock;
        this.strategy = groupedStrategy;
    }

    // ------------------------------------------------------------------ Observer (subject)

    public void registerObserver(CalendarObserver observer) {
        observers.add(observer);
    }

    public void removeObserver(CalendarObserver observer) {
        observers.remove(observer);
    }

    public void notifyObservers(CalendarEvent event) {
        for (CalendarObserver observer : observers) {
            observer.update(event);
        }
    }

    // ------------------------------------------------------------------ Strategy (context)

    public void setStrategy(SchedulingStrategy strategy) {
        this.strategy = strategy;
    }

    public SchedulingStrategy getStrategy() {
        return strategy;
    }

    /** Tries grouping first and switches to the fallback strategy when grouping is impossible. */
    private SchedulePlan choosePlan(SchedulingContext context) {
        setStrategy(groupedStrategy);
        Optional<SchedulePlan> plan = strategy.plan(context);
        if (plan.isEmpty()) {
            setStrategy(fallbackStrategy);
            plan = strategy.plan(context);
        }
        return plan.orElseThrow();
    }

    // ------------------------------------------------------------------ F02: generate

    /** Plans (or re-plans) one plant's waterings and stores them as an undoable command. */
    @Transactional
    public synchronized ScheduleResult generate(long userId, long plantId) {
        OwnedPlant plant = plantRepository.findOwned(userId, plantId);
        WateringInterval interval = WateringInterval.from(plant.getCare());
        LocalDateTime now = LocalDateTime.now(clock);
        Availability availability = availabilityFor(userId);

        Map<String, List<LocalDateTime>> others = new LinkedHashMap<>();
        Map<String, Integer> otherIntervals = new LinkedHashMap<>();
        for (OwnedPlant other : plantRepository.findAll(userId)) {
            if (other.getId().equals(plant.getId()) || other.getWateringIntervalDays() == null) {
                continue;
            }
            List<LocalDateTime> times = futurePlanned(other.getId(), now).stream().map(CalendarEntry::getDueAt).toList();
            if (!times.isEmpty()) {
                String key = uniqueKey(others, other.getDisplayName());
                others.put(key, times);
                otherIntervals.put(key, other.getWateringIntervalDays());
            }
        }
        SchedulePlan plan = choosePlan(context(plant, interval, others, otherIntervals, availability, now));
        plant.setWateringInterval(plan.intervalDays(), interval.quality());
        plantRepository.save(plant);

        List<EntrySpec> specs = plan.times().stream()
                .map(t -> new EntrySpec(userId, plantId, t, plan.grouped())).toList();
        List<CalendarEntry> existing = futurePlanned(plantId, now);
        if (existing.isEmpty()) {
            commandHistory.run(userId, new AddEntriesCommand(this, userId,
                    "Added " + specs.size() + " waterings for " + plant.getDisplayName(), specs));
        } else {
            commandHistory.run(userId, new RescheduleCommand(this, userId,
                    "Regenerated the schedule for " + plant.getDisplayName(), existing, specs));
        }
        log.info("Scheduled {} with {} ({} entries)", plant.getDisplayName(), strategy.name(), specs.size());

        String warning = switch (interval.quality()) {
            case GOOD -> null;
            case ESTIMATED -> "The care data only gave a watering category, so the interval ("
                    + plan.intervalDays() + " days) is an estimate.";
            case MISSING -> "No watering information was found for this plant, so a " + plan.intervalDays()
                    + "-day default was used. Check the soil before watering.";
        };
        return new ScheduleResult(plantId, plant.getDisplayName(), plan.intervalDays(), interval.quality(),
                interval.basis(), strategy.name(), plan.grouped(), plan.groupedWith(), plan.reason(),
                plan.times().stream().limit(5).toList(), specs.size(), warning, null, false);
    }

    private SchedulingContext context(OwnedPlant plant, WateringInterval interval,
                                      Map<String, List<LocalDateTime>> others, Map<String, Integer> otherIntervals,
                                      Availability availability, LocalDateTime now) {
        LocalDate tomorrow = now.toLocalDate().plusDays(1);
        LocalDate earliest = tomorrow;
        if (plant.getLastWateredAt() != null) {
            LocalDate next = plant.getLastWateredAt().toLocalDate().plusDays(interval.days());
            earliest = next.isAfter(tomorrow) ? next : tomorrow;
        }
        return new SchedulingContext(plant.getDisplayName(), interval, earliest,
                now.toLocalDate().plusDays(HORIZON_DAYS), others, otherIntervals, availability);
    }

    // ------------------------------------------------------------------ F03: availability

    /**
     * Parses the user's availability with the parser the factory picks, then
     * re-plans every plant's future waterings around it as one undoable change.
     */
    @Transactional
    public synchronized RescheduleResult applyAvailability(long userId, AvailabilityInput input) {
        AvailabilityImporter importer = importerFactory.create(input.type());
        Availability availability = importer.parse(input);
        LocalDateTime now = LocalDateTime.now(clock);
        availabilityProfiles.save(new AvailabilityProfile(userId, jsonMapper.writeValueAsString(availability), now));

        List<CalendarEntry> before = entries.findByOwnerIdAndStatusAndDueAtAfterOrderByDueAt(userId,
                CalendarEntry.Status.PLANNED, now);
        List<OwnedPlant> plants = new ArrayList<>(plantRepository.findAll(userId));
        plants.sort(Comparator.comparingInt(p -> WateringInterval.from(p.getCare()).days()));

        Map<String, List<LocalDateTime>> planned = new LinkedHashMap<>();
        Map<String, Integer> intervals = new LinkedHashMap<>();
        List<EntrySpec> specs = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        for (OwnedPlant plant : plants) {
            WateringInterval interval = WateringInterval.from(plant.getCare());
            SchedulePlan plan = choosePlan(context(plant, interval, planned, intervals, availability, now));
            String key = uniqueKey(planned, plant.getDisplayName());
            planned.put(key, plan.times());
            intervals.put(key, plan.intervalDays());
            unmetNeed(plant.getDisplayName(), plan, now).ifPresent(warnings::add);
            plant.setWateringInterval(plan.intervalDays(), interval.quality());
            plantRepository.save(plant);
            plan.times().forEach(t -> specs.add(new EntrySpec(userId, plant.getId(), t, plan.grouped())));
        }
        if (!plants.isEmpty()) {
            commandHistory.run(userId, new RescheduleCommand(this, userId,
                    "Rescheduled " + specs.size() + " waterings to fit your availability", before, specs));
        }
        return new RescheduleResult(availability, plants.size(), before.size(), specs.size(),
                examples(plants, before, specs), warnings, null);
    }

    /**
     * Whether the availability leaves this plant without water for too long:
     * fitting waterings to free time may move each one a few days, so a gap is
     * only reported when it exceeds the interval by half (at least 2 days).
     * Package-private for tests.
     */
    static Optional<String> unmetNeed(String plantName, SchedulePlan plan, LocalDateTime now) {
        int interval = plan.intervalDays();
        if (interval <= 0 || plan.times().isEmpty()) {
            return Optional.empty();
        }
        long allowed = interval + Math.max(2, (interval + 1) / 2);
        long longest = 0;
        LocalDateTime previous = now;
        for (LocalDateTime time : plan.times()) {
            long gap = java.time.Duration.between(previous, time).toHours() / 24;
            longest = Math.max(longest, gap);
            previous = time;
        }
        return longest > allowed
                ? Optional.of(plantName + " needs water every " + interval + " days, but your availability leaves "
                        + "gaps of up to " + longest + " days between waterings. Ask someone to water it, or add more free time.")
                : Optional.empty();
    }

    private static List<String> examples(List<OwnedPlant> plants, List<CalendarEntry> before, List<EntrySpec> after) {
        List<String> lines = new ArrayList<>();
        for (OwnedPlant plant : plants) {
            Optional<LocalDateTime> oldNext = before.stream().filter(e -> e.getPlantId() == plant.getId())
                    .map(CalendarEntry::getDueAt).findFirst();
            Optional<LocalDateTime> newNext = after.stream().filter(e -> e.plantId() == plant.getId())
                    .map(EntrySpec::dueAt).findFirst();
            if (newNext.isPresent()) {
                lines.add(plant.getDisplayName() + ": next watering " + newNext.get().format(SHORT)
                        + oldNext.filter(o -> !o.equals(newNext.get())).map(o -> " (was " + o.format(SHORT) + ")").orElse(""));
            }
            if (lines.size() == 5) {
                break;
            }
        }
        return lines;
    }

    @Transactional(readOnly = true)
    public Availability availabilityFor(long userId) {
        return availabilityProfiles.findById(userId)
                .map(p -> jsonMapper.readValue(p.getJson(), Availability.class))
                .orElse(Availability.none());
    }

    // ------------------------------------------------------------------ Command support

    /** Undoes the user's most recent calendar change (Stage 1 ScheduleManager.undoLast()). */
    @Transactional
    public synchronized Optional<String> undoLast(long userId) {
        return commandHistory.undoLast(userId);
    }

    public Optional<String> lastChange(long userId) {
        return commandHistory.peekLast(userId);
    }

    /**
     * Receiver operation used by the commands: removes the given planned
     * entries, adds new ones, and notifies the observers once.
     */
    @Transactional
    public List<CalendarEntry> replaceEntries(long ownerId, Collection<Long> removeIds, List<EntrySpec> add,
                                              CalendarEvent.Type eventType) {
        Set<Long> plantIds = new HashSet<>();
        List<CalendarEntry> toRemove = entries.findAllById(removeIds).stream()
                .filter(e -> e.getStatus() == CalendarEntry.Status.PLANNED).toList();
        toRemove.forEach(e -> plantIds.add(e.getPlantId()));
        entries.deleteAll(toRemove);
        List<CalendarEntry> created = entries.saveAll(add.stream().map(EntrySpec::toEntry).toList());
        created.forEach(e -> plantIds.add(e.getPlantId()));
        entries.flush();
        notifyObservers(new CalendarEvent(eventType, ownerId, plantIds));
        return created;
    }

    // ------------------------------------------------------------------ F04 support

    /** Planned entries that are due (at or before {@code time}), for every user. */
    @Transactional(readOnly = true)
    public List<CalendarEntry> getDueEntries(LocalDateTime time) {
        return entries.findByStatusAndDueAtLessThanEqualOrderByDueAt(CalendarEntry.Status.PLANNED, time);
    }

    @Transactional(readOnly = true)
    public Optional<LocalDateTime> nextDue(long plantId) {
        return entries.findByPlantIdAndStatusOrderByDueAt(plantId, CalendarEntry.Status.PLANNED).stream()
                .map(CalendarEntry::getDueAt).findFirst();
    }

    @Transactional(readOnly = true)
    public List<CalendarEntry> plannedBetween(long userId, LocalDateTime from, LocalDateTime to) {
        return entries.findByOwnerIdAndDueAtBetweenOrderByDueAt(userId, from, to).stream()
                .filter(e -> e.getStatus() == CalendarEntry.Status.PLANNED).toList();
    }

    @Transactional(readOnly = true)
    public List<CalendarEntry> plannedBefore(long userId, LocalDateTime time) {
        return entries.findByOwnerIdAndStatusAndDueAtBeforeOrderByDueAt(userId, CalendarEntry.Status.PLANNED, time);
    }

    /** Calendar page: every entry in a date range, with plant names. */
    @Transactional(readOnly = true)
    public List<CalendarItem> calendar(long userId, LocalDate from, LocalDate to) {
        Map<Long, String> names = plantRepository.findAll(userId).stream()
                .collect(Collectors.toMap(OwnedPlant::getId, OwnedPlant::getDisplayName));
        return entries.findByOwnerIdAndDueAtBetweenOrderByDueAt(userId, from.atStartOfDay(), to.plusDays(1).atStartOfDay())
                .stream()
                .filter(e -> names.containsKey(e.getPlantId()))
                .map(e -> new CalendarItem(e.getId(), e.getPlantId(), names.get(e.getPlantId()), e.getDueAt(),
                        e.getStatus().name(), e.isGrouped(), e.getCompletedAt()))
                .toList();
    }

    /**
     * Marks the plant's due waterings as done (Stage 1 completeEntry()): every
     * planned entry up to half an interval ahead counts as covered by this
     * watering. Then tops the schedule up and notifies the observers.
     */
    @Transactional
    public int completeEntry(long plantId) {
        OwnedPlant plant = plantRepository.findById(plantId);
        LocalDateTime now = LocalDateTime.now(clock);
        int interval = plant.getWateringIntervalDays() != null ? plant.getWateringIntervalDays() : 7;
        LocalDateTime coveredUntil = now.plusHours(Math.max(12, interval * 12L));
        int completed = 0;
        for (CalendarEntry entry : entries.findByPlantIdAndStatusOrderByDueAt(plantId, CalendarEntry.Status.PLANNED)) {
            if (entry.getDueAt().isAfter(coveredUntil)) {
                break;
            }
            entry.complete(now);
            entries.save(entry);
            completed++;
        }
        ensureHorizon(plant, now);
        notifyObservers(new CalendarEvent(CalendarEvent.Type.COMPLETED, plant.getOwnerId(), Set.of(plantId)));
        return completed;
    }

    /**
     * F04 "mark as watered" (Stage 1 AgentController.markWatered() steps):
     * the plant's State handles the watering (OwnedPlant.markWatered()), the
     * plant is saved, and the covered calendar entries are completed, which
     * notifies the observers.
     */
    @Transactional
    public String recordWatering(long userId, long plantId) {
        OwnedPlant plant = plantRepository.findOwned(userId, plantId);
        String message = plant.markWatered(LocalDateTime.now(clock));
        plantRepository.save(plant);
        completeEntry(plantId);
        return message + nextDue(plantId).map(d -> " Next watering: " + d.format(SHORT) + ".").orElse("");
    }

    /** Deletes a removed plant's calendar entries. */
    @Transactional
    public void removePlant(long ownerId, long plantId) {
        entries.deleteByPlantId(plantId);
        notifyObservers(new CalendarEvent(CalendarEvent.Type.REMOVED, ownerId, Set.of(plantId)));
    }

    /** Daily: keeps about eight weeks of waterings planned for every plant. */
    @Scheduled(cron = "0 5 0 * * *")
    @Transactional
    public void extendSchedules() {
        LocalDateTime now = LocalDateTime.now(clock);
        for (OwnedPlant plant : plantRepository.findAllOwners()) {
            if (plant.getWateringIntervalDays() != null) {
                ensureHorizon(plant, now);
            }
        }
    }

    private void ensureHorizon(OwnedPlant plant, LocalDateTime now) {
        List<CalendarEntry> future = futurePlanned(plant.getId(), now);
        if (future.size() >= 3 || plant.getWateringIntervalDays() == null) {
            return;
        }
        int interval = plant.getWateringIntervalDays();
        LocalDateTime last = future.isEmpty() ? now : future.get(future.size() - 1).getDueAt();
        Availability availability = availabilityFor(plant.getOwnerId());
        LocalDate end = now.toLocalDate().plusDays(HORIZON_DAYS);
        List<CalendarEntry> added = new ArrayList<>();
        for (LocalDate d = last.toLocalDate().plusDays(interval); !d.isAfter(end); d = d.plusDays(interval)) {
            added.add(new CalendarEntry(plant.getOwnerId(), plant.getId(),
                    AvailabilityFitter.fit(d, availability, now.toLocalDate().plusDays(1)), false));
        }
        if (!added.isEmpty()) {
            entries.saveAll(added);
            notifyObservers(new CalendarEvent(CalendarEvent.Type.ENTRIES_ADDED, plant.getOwnerId(), Set.of(plant.getId())));
        }
    }

    private List<CalendarEntry> futurePlanned(long plantId, LocalDateTime now) {
        return entries.findByPlantIdAndStatusOrderByDueAt(plantId, CalendarEntry.Status.PLANNED).stream()
                .filter(e -> e.getDueAt().isAfter(now)).toList();
    }

    private static String uniqueKey(Map<String, ?> map, String name) {
        String key = name;
        for (int i = 2; map.containsKey(key); i++) {
            key = name + " (" + i + ")";
        }
        return key;
    }

    /** Exposed for tests and the GUI: is the plant's interval only a guess? */
    public static boolean isThinData(IntervalQuality quality) {
        return quality != IntervalQuality.GOOD;
    }
}
