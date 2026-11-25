package com.plantorganizer.agent;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.plantorganizer.common.InvalidInputException;
import com.plantorganizer.health.HealthReport;
import com.plantorganizer.identification.IdentificationResult;
import com.plantorganizer.identification.PlantIdentifier;
import com.plantorganizer.identification.PlantImage;
import com.plantorganizer.memory.AgentMemory;
import com.plantorganizer.memory.ChatMessage;
import com.plantorganizer.memory.UserPreference;
import com.plantorganizer.notification.Notification;
import com.plantorganizer.notification.NotificationService;
import com.plantorganizer.notification.WateringOverview;
import com.plantorganizer.plantinfo.PlantCareProfile;
import com.plantorganizer.plantinfo.PlantQuery;
import com.plantorganizer.plants.CareNotes;
import com.plantorganizer.plants.DesiredPlant;
import com.plantorganizer.plants.HealthRecord;
import com.plantorganizer.plants.OwnedPlant;
import com.plantorganizer.plants.PlantNote;
import com.plantorganizer.plants.PlantPhoto;
import com.plantorganizer.plants.PlantRepository;
import com.plantorganizer.recommendation.RecommendationEngine;
import com.plantorganizer.recommendation.RecommendedPlant;
import com.plantorganizer.schedule.CalendarItem;
import com.plantorganizer.schedule.RescheduleResult;
import com.plantorganizer.schedule.ScheduleManager;
import com.plantorganizer.schedule.ScheduleResult;
import com.plantorganizer.schedule.availability.Availability;
import com.plantorganizer.schedule.availability.AvailabilityInput;
import com.plantorganizer.sharing.FriendView;
import com.plantorganizer.sharing.PlantSummary;
import com.plantorganizer.sharing.ShareLinkView;
import com.plantorganizer.sharing.ShareService;
import com.plantorganizer.sharing.SharedScheduleView;
import com.plantorganizer.spaces.LightLevel;
import com.plantorganizer.spaces.SpaceComponent;
import com.plantorganizer.spaces.SpaceManager;
import com.plantorganizer.spaces.SpaceView;
import com.plantorganizer.user.User;
import com.plantorganizer.user.UserRepository;
import com.plantorganizer.websearch.ResearchResult;

/**
 * Facade (Facade pattern): the single entry point both user interfaces call.
 *
 * The GUI pages (through the REST controllers in the web package) and the
 * chat box CLI (through {@link #chat}) call simple methods such as
 * identifyPlant(), addOwnedPlant() and markWatered() instead of coordinating
 * PlantAgent, ScheduleManager, SpaceManager, ShareService,
 * RecommendationEngine, NotificationService and PlantRepository themselves.
 *
 * AI and hybrid work goes through PlantAgent (plan -> tools -> explanation);
 * purely deterministic operations (lists, notes, toggles, friends, links) call
 * the subsystem directly, as in the Stage 1 sequence diagrams.
 *
 * Note: despite the Stage 1 name, this is a Spring @Service, not a Spring MVC
 * controller; the HTTP layer lives in the web package.
 */
@Service
public class AgentController {

    private final PlantAgent agent;
    private final ChatAgent chatAgent;
    private final PlantRepository plantRepository;
    private final ScheduleManager scheduleManager;
    private final NotificationService notificationService;
    private final SpaceManager spaceManager;
    private final RecommendationEngine recommendationEngine;
    private final ShareService shareService;
    private final AgentMemory memory;
    private final UserRepository userRepository;
    private final Clock clock;

    public AgentController(PlantAgent agent, ChatAgent chatAgent, PlantRepository plantRepository,
                           ScheduleManager scheduleManager, NotificationService notificationService,
                           SpaceManager spaceManager, RecommendationEngine recommendationEngine,
                           ShareService shareService, AgentMemory memory, UserRepository userRepository, Clock clock) {
        this.agent = agent;
        this.chatAgent = chatAgent;
        this.plantRepository = plantRepository;
        this.scheduleManager = scheduleManager;
        this.notificationService = notificationService;
        this.spaceManager = spaceManager;
        this.recommendationEngine = recommendationEngine;
        this.shareService = shareService;
        this.memory = memory;
        this.userRepository = userRepository;
        this.clock = clock;
    }

    // ================================================================ users (no login; see CurrentUser)

    public List<User> listUsers() {
        return userRepository.findAll();
    }

    public User createUser(String displayName) {
        if (displayName == null || displayName.isBlank()) {
            throw new InvalidInputException("Enter a name.");
        }
        return userRepository.save(new User(displayName.trim(), clock.instant()));
    }

    public User getUser(long userId) {
        return userRepository.findById(userId);
    }

    // ================================================================ F01 identify

    /** F01 steps 1-3: identify the plant in 1-5 photos and return the top 3 candidates. */
    public IdentificationResult identifyPlant(long userId, List<PlantImage> photos) {
        if (photos == null || photos.isEmpty()) {
            throw new InvalidInputException("Please upload at least one photo of the plant.");
        }
        if (photos.size() > PlantIdentifier.MAX_IMAGES) {
            throw new InvalidInputException("Please upload at most " + PlantIdentifier.MAX_IMAGES + " photos at a time.");
        }
        return agent.run(AgentRequest.identifyFromPhotos(userId, photos)).resultAs(IdentificationResult.class);
    }

    /** F01 text path: the agent proposes species from a description; only ones found in Perenual are kept. */
    public IdentificationResult identifyByText(long userId, String description) {
        if (description == null || description.trim().length() < 3) {
            throw new InvalidInputException("Describe the plant in a few words (leaves, colour, size, flowers).");
        }
        return agent.run(AgentRequest.identifyFromText(userId, description)).resultAs(IdentificationResult.class);
    }

    /** F01 steps 4-6: care profile plus the agent's care and placement explanation (using the user's spaces). */
    public PlantReport confirmCandidate(long userId, String scientificName, String commonName) {
        if (scientificName == null || scientificName.isBlank()) {
            throw new InvalidInputException("A scientific name is required to look up care information.");
        }
        return agent.run(AgentRequest.explainConfirmedPlant(userId, PlantQuery.of(scientificName, commonName)))
                .resultAs(PlantReport.class);
    }

    // ================================================================ F08 owned plants (+ F02 schedule)

    /** UC02: saves the plant with its care notes and has the agent generate its watering schedule (UC03). */
    public AddPlantResult addOwnedPlant(long userId, String scientificName, String commonName, String nickname,
                                        Long spaceId) {
        if (scientificName == null || scientificName.isBlank()) {
            throw new InvalidInputException("A plant name is required.");
        }
        return agent.run(AgentRequest.addOwnedPlant(userId, PlantQuery.of(scientificName, commonName), nickname, spaceId))
                .resultAs(AddPlantResult.class);
    }

    @Transactional(readOnly = true)
    public List<OwnedPlantView> listOwnedPlants(long userId) {
        return plantRepository.findAll(userId).stream().map(this::view).toList();
    }

    @Transactional(readOnly = true)
    public OwnedPlantView getOwnedPlant(long userId, long plantId) {
        return view(plantRepository.findOwned(userId, plantId));
    }

    /** F08: the user edits the care notes (fields the agent could not fill stay empty until then). */
    @Transactional
    public OwnedPlantView updateCareNotes(long userId, long plantId, String nickname, PlantCareProfile edits) {
        OwnedPlant plant = plantRepository.findOwned(userId, plantId);
        plant.setNickname(nickname);
        if (edits != null) {
            plant.getCare().applyEdits(edits);
        }
        return view(plantRepository.save(plant));
    }

    @Transactional
    public void deleteOwnedPlant(long userId, long plantId) {
        OwnedPlant plant = plantRepository.findOwned(userId, plantId);
        if (plant.getSpot() != null) {
            spaceManager.unassignPlant(userId, plantId);
        }
        scheduleManager.removePlant(userId, plantId);
        plantRepository.delete(plant);
        recommendationEngine.markStale(userId);
    }

    public List<PlantNote> listNotes(long userId, long plantId) {
        plantRepository.findOwned(userId, plantId);
        return plantRepository.findNotes(plantId);
    }

    public PlantNote saveNote(long userId, long plantId, String text) {
        if (text == null || text.isBlank()) {
            throw new InvalidInputException("The note is empty.");
        }
        plantRepository.findOwned(userId, plantId);
        return plantRepository.saveNote(plantId, PlantNote.Kind.USER, text.trim());
    }

    public void deleteNote(long userId, long plantId, long noteId) {
        plantRepository.findOwned(userId, plantId);
        plantRepository.deleteNote(plantId, noteId);
    }

    // ================================================================ F02 / F03 schedule

    public ScheduleResult generateSchedule(long userId, long plantId) {
        return agent.run(AgentRequest.generateSchedule(userId, plantId)).resultAs(ScheduleResult.class);
    }

    /** F02 "offer to search for longer": research the watering needs on the web, then re-plan. */
    public ScheduleResult researchWatering(long userId, long plantId) {
        OwnedPlant plant = plantRepository.findOwned(userId, plantId);
        return agent.run(AgentRequest.researchWatering(userId, plantId, plant.getScientificName(), plant.getDisplayName()))
                .resultAs(ScheduleResult.class);
    }

    /** F03 (UC04): text, timetable photo or iCal file in; the calendar is rebuilt around it (undoable). */
    public RescheduleResult updateSchedule(long userId, AvailabilityInput input) {
        return agent.run(AgentRequest.updateSchedule(userId, input)).resultAs(RescheduleResult.class);
    }

    @SuppressWarnings("unchecked")
    public Optional<String> undoScheduleChange(long userId) {
        return agent.run(AgentRequest.undoSchedule(userId)).resultAs(Optional.class);
    }

    public Optional<String> lastScheduleChange(long userId) {
        return scheduleManager.lastChange(userId);
    }

    public Availability getAvailability(long userId) {
        return scheduleManager.availabilityFor(userId);
    }

    public List<CalendarItem> getCalendar(long userId, LocalDate from, LocalDate to) {
        if (to.isBefore(from) || from.plusDays(120).isBefore(to)) {
            throw new InvalidInputException("Choose a date range of up to 120 days.");
        }
        return scheduleManager.calendar(userId, from, to);
    }

    // ================================================================ F04 notifications

    public WateringOverview getWateringOverview(long userId) {
        return notificationService.getWateringOverview(userId);
    }

    /** UC05: OwnedPlant.markWatered() (State) -> save -> ScheduleManager.completeEntry() -> observers. */
    public String markWatered(long userId, long plantId) {
        return scheduleManager.recordWatering(userId, plantId);
    }

    public void setNotifications(long userId, long plantId, boolean enabled) {
        notificationService.setEnabled(userId, plantId, enabled);
    }

    /** The answer to the agent's first-time question "Would you like reminders?" (also used for new plants). */
    @Transactional
    public void setNotificationDefault(long userId, boolean enabled, Long plantId) {
        User user = userRepository.findById(userId);
        user.setNotificationsAsked(true);
        user.setNotifyByDefault(enabled);
        userRepository.save(user);
        if (plantId != null) {
            notificationService.setEnabled(userId, plantId, enabled);
        }
    }

    public List<Notification> listNotifications(long userId) {
        return notificationService.list(userId);
    }

    public void markNotificationsRead(long userId) {
        notificationService.markAllRead(userId);
    }

    // ================================================================ F05 health

    /**
     * UC06. {@code fromPlantPage} is true when started from the plant's own page,
     * where the species is already known, so the Pl@ntNet species check is skipped.
     */
    public HealthReport analyzeHealth(long userId, long plantId, PlantImage photo, String description,
                                      boolean fromPlantPage) {
        return agent.run(AgentRequest.analyzeHealth(userId, plantId, photo, description, !fromPlantPage))
                .resultAs(HealthReport.class);
    }

    /** UC06 alternative: the user is not satisfied, so the agent searches the web for more. */
    public ResearchResult researchHealth(long userId, long recordId) {
        HealthRecord record = plantRepository.findHealthRecord(recordId);
        OwnedPlant plant = plantRepository.findOwned(userId, record.getPlantId());
        return agent.run(AgentRequest.researchHealth(userId, plant.getScientificName(),
                "My " + plant.getDisplayName() + " (" + plant.getScientificName() + ") shows: "
                        + (record.getIssues() != null ? record.getIssues().replace('\n', ';') : "unknown problems")
                        + ". The advice so far was: " + (record.getCareSteps() != null ? record.getCareSteps().replace('\n', ';') : "none")
                        + ". It didn't fully help. What else could cause this, and what should I check or try?",
                "Owner's description: " + (record.getUserDescription() != null ? record.getUserDescription() : "none")))
                .resultAs(ResearchResult.class);
    }

    public List<HealthRecord> healthHistory(long userId, long plantId) {
        plantRepository.findOwned(userId, plantId);
        return plantRepository.findHealthRecords(plantId);
    }

    public PlantPhoto getPhoto(long userId, long plantId, long photoId) {
        plantRepository.findOwned(userId, plantId);
        return plantRepository.findPhoto(photoId).filter(p -> p.getPlantId() == plantId)
                .orElseThrow(() -> new com.plantorganizer.common.NotFoundException("Photo not found."));
    }

    /** Saves a health result (or any agent text) into the plant's notes, to track its health over time. */
    public PlantNote saveHealthNote(long userId, long recordId) {
        HealthRecord r = plantRepository.findHealthRecord(recordId);
        plantRepository.findOwned(userId, r.getPlantId());
        String text = "Health check (" + r.getStatus().name().replace('_', ' ').toLowerCase() + ", " + r.getHealthScore()
                + "/10): " + r.getSummary() + (r.getCareSteps() != null ? "\nWhat to do:\n- " + r.getCareSteps().replace("\n", "\n- ") : "");
        return plantRepository.saveNote(r.getPlantId(), PlantNote.Kind.HEALTH, text);
    }

    public PlantNote saveAgentNote(long userId, long plantId, String text) {
        plantRepository.findOwned(userId, plantId);
        return plantRepository.saveNote(plantId, PlantNote.Kind.AGENT, text);
    }

    // ================================================================ F06 / F07 recommendations

    /** UC07: shows the list, refreshing it first when the owned list changed since the last refresh. */
    public RecommendationList getRecommendations(long userId, RecommendationEngine.SortBy sortBy) {
        User user = userRepository.findById(userId);
        boolean hasData = knowsUserTaste(userId);
        String message = null;
        boolean refreshed = false;
        if (user.isRecommendationsStale() && hasData) {
            try {
                agent.run(AgentRequest.recommendLearned(userId));
                refreshed = true;
            } catch (com.plantorganizer.common.ExternalServiceException e) {
                message = "Couldn't refresh recommendations right now: " + e.getMessage();
            }
        }
        if (!hasData) {
            message = "Add a few plants (or tell the agent what you like) and recommendations will appear here.";
        }
        return new RecommendationList(sortRecommendations(userId, sortBy), refreshed, message);
    }

    /** UC07 "refresh": rebuilds the automatic list. An error when the agent knows nothing about the user yet. */
    public List<RecommendedPlant> refreshRecommendations(long userId) {
        userRepository.findById(userId);
        if (!knowsUserTaste(userId)) {
            throw new InvalidInputException("The agent doesn't know anything about you yet. Add a few plants "
                    + "(or keep some recommendations you asked for) and try again.");
        }
        agent.run(AgentRequest.recommendLearned(userId));
        return sortRecommendations(userId, RecommendationEngine.SortBy.CARE);
    }

    public List<RecommendedPlant> sortRecommendations(long userId, RecommendationEngine.SortBy sortBy) {
        return recommendationEngine.list(userId, sortBy);
    }

    @SuppressWarnings("unchecked")
    public List<RecommendedPlant> getRecommendationsFor(long userId, String constraints) {
        return agent.run(AgentRequest.recommendFor(userId, constraints)).resultAs(List.class);
    }

    public RecommendedPlant acceptRecommendation(long userId, long id) {
        return recommendationEngine.accept(userId, id);
    }

    public void unrecommend(long userId, long id) {
        recommendationEngine.unrecommend(userId, id);
    }

    public List<UserPreference> listPreferences(long userId) {
        return memory.listPreferences(userId);
    }

    public void forgetPreference(long userId, long preferenceId) {
        memory.forget(userId, preferenceId);
        recommendationEngine.markStale(userId);
    }

    /** F06 list plus a status message for the page. */
    public record RecommendationList(List<RecommendedPlant> items, boolean refreshed, String message) {
    }

    // ================================================================ F09 desired plants

    public DesiredPlant addDesiredPlant(long userId, String scientificName, String commonName, Long spaceId,
                                        String note, String imageUrl) {
        if (scientificName == null || scientificName.isBlank()) {
            throw new InvalidInputException("A plant name is required.");
        }
        DesiredPlant desired = agent.run(AgentRequest.addDesiredPlant(userId, scientificName.trim(), commonName,
                spaceId, note, imageUrl)).resultAs(DesiredPlant.class);
        if (spaceId != null) {
            checkDesiredPlantFit(userId, desired.getId());
            return plantRepository.findDesiredById(userId, desired.getId());
        }
        return desired;
    }

    public List<DesiredPlant> listDesiredPlants(long userId) {
        return plantRepository.findDesired(userId);
    }

    /** Changing the intended space re-runs the fit check, so the agent's feedback matches the new choice. */
    @Transactional
    public DesiredPlant updateDesiredPlant(long userId, long id, Long spaceId, String note) {
        DesiredPlant desired = plantRepository.findDesiredById(userId, id);
        if (spaceId != null) {
            spaceManager.getSpace(userId, spaceId);
        }
        desired.setIntendedSpaceId(spaceId);
        desired.setNote(note);
        plantRepository.saveDesired(desired);
        if (spaceId != null) {
            checkDesiredPlantFit(userId, id);
        }
        return plantRepository.findDesiredById(userId, id);
    }

    /** UC09: compares the plant's needs with the intended space's light. */
    public DesiredFit checkDesiredPlantFit(long userId, long desiredId) {
        DesiredPlant desired = plantRepository.findDesiredById(userId, desiredId);
        if (desired.getIntendedSpaceId() == null) {
            throw new InvalidInputException("Choose the space you'd put " + desired.getDisplayName() + " in first.");
        }
        SpaceComponent space = spaceManager.getSpace(userId, desired.getIntendedSpaceId());
        return DesiredFitRequests.check(agent, plantRepository, userId, desired, space, LocalDateTime.now(clock));
    }

    /** UC09 alternative "no care info": the agent searches the web for the plant's light needs. */
    public ResearchResult researchDesiredPlant(long userId, long desiredId) {
        DesiredPlant desired = plantRepository.findDesiredById(userId, desiredId);
        String space = desired.getIntendedSpaceId() == null ? "a typical home"
                : spaceManager.getSpace(userId, desired.getIntendedSpaceId()).path();
        return agent.run(AgentRequest.researchHealth(userId, desired.getScientificName(),
                "What light, watering and care does " + desired.getDisplayName() + " (" + desired.getScientificName()
                        + ") need indoors, and would it do well in " + space + "?", null)).resultAs(ResearchResult.class);
    }

    public void removeDesiredPlant(long userId, long id) {
        plantRepository.deleteDesired(plantRepository.findDesiredById(userId, id));
    }

    /** "I bought it": moves a wishlist plant to the owned list (with care notes and a schedule). */
    public AddPlantResult buyDesiredPlant(long userId, long id) {
        DesiredPlant desired = plantRepository.findDesiredById(userId, id);
        Long spaceId = desired.getIntendedSpaceId();
        AddPlantResult result = addOwnedPlant(userId, desired.getScientificName(), desired.getCommonName(), null, spaceId);
        plantRepository.deleteDesired(desired);
        return result;
    }

    // ================================================================ F10 spaces

    public SpaceView createSpace(long userId, String name, LightLevel light, Long parentId, boolean spot) {
        return spaceManager.createSpace(userId, name, light, parentId, spot);
    }

    public List<SpaceView> listSpaces(long userId) {
        return spaceManager.listSpaces(userId);
    }

    public SpaceView getSpace(long userId, long spaceId) {
        return spaceManager.getSpaceView(userId, spaceId);
    }

    @Transactional(readOnly = true)
    public List<OwnedPlantView> getPlantsInSpace(long userId, long spaceId) {
        return spaceManager.getPlants(userId, spaceId).stream().map(this::view).toList();
    }

    public SpaceView assignPlant(long userId, long plantId, long spaceId) {
        return agent.run(AgentRequest.placePlant(userId, plantId, spaceId)).resultAs(SpaceView.class);
    }

    public void unassignPlant(long userId, long plantId) {
        spaceManager.unassignPlant(userId, plantId);
    }

    public SpaceView updateSpace(long userId, long spaceId, String name, LightLevel light, boolean inheritLight) {
        return spaceManager.updateSpace(userId, spaceId, name, light, inheritLight);
    }

    public void moveSpace(long userId, long spaceId, double x, double y) {
        spaceManager.moveSpace(userId, spaceId, x, y);
    }

    public void deleteSpace(long userId, long spaceId) {
        spaceManager.deleteSpace(userId, spaceId);
    }

    // ================================================================ F11 friends

    public FriendView addFriend(long userId, long friendId) {
        return shareService.addFriend(userId, friendId);
    }

    public void removeFriend(long userId, long friendId) {
        shareService.removeFriend(userId, friendId);
    }

    public FriendView setFriendCanView(long userId, long friendId, boolean allowed) {
        return shareService.setViewerAllowed(userId, friendId, allowed);
    }

    public List<FriendView> listFriends(long userId) {
        return shareService.listFriends(userId);
    }

    public List<PlantSummary> viewFriendPlants(long viewerId, long friendId) {
        return shareService.viewFriendPlants(viewerId, friendId);
    }

    // ================================================================ F12 share links

    public ShareLinkView createShareLink(long userId, List<Long> plantIds, String label) {
        return shareService.createShareLink(userId, plantIds, label);
    }

    public List<ShareLinkView> listShareLinks(long userId) {
        return shareService.listShareLinks(userId);
    }

    public void revokeShareLink(long userId, String token) {
        shareService.revokeShareLink(userId, token);
    }

    public SharedScheduleView openShareLink(String token) {
        return shareService.openShareLink(token);
    }

    // ================================================================ CLI (chat box)

    public ChatReply chat(long userId, String message, PlantImage photo) {
        return chatAgent.chat(userId, message, photo);
    }

    public ChatReply chatAction(long userId, String type, Map<String, Object> payload) {
        return chatAgent.act(userId, type, payload);
    }

    /** Read-only chat used by the terminal CLI (Agent.jar): answers questions, suggests commands for changes. */
    public ChatReply askAgent(long userId, String message) {
        return chatAgent.ask(userId, message);
    }

    /** Which action does this text ask for? Nothing is run or stored (Agent.jar: "did you mean ...?"). */
    public SuggestedAction interpretRequest(long userId, String text) {
        return chatAgent.interpret(userId, text);
    }

    public List<ChatMessage> chatHistory(long userId) {
        return chatAgent.history(userId);
    }

    public void clearChat(long userId) {
        chatAgent.clear(userId);
    }

    // ================================================================ helpers

    /** Automatic recommendations need something to learn from: owned plants or liked plants. */
    private boolean knowsUserTaste(long userId) {
        return !plantRepository.findAll(userId).isEmpty() || !memory.getPreferences(userId).likes().isEmpty();
    }

    private OwnedPlantView view(OwnedPlant p) {
        List<HealthRecord> health = plantRepository.findHealthRecords(p.getId());
        return new OwnedPlantView(p.getId(), p.getDisplayName(), p.getNickname(), p.getScientificName(),
                p.getCommonName(), p.getCare().toProfile(p.getScientificName(), p.getCommonName()),
                p.getWateringIntervalDays(), p.getIntervalQuality(), p.getWateringStatus(), p.getLastWateredAt(),
                scheduleManager.nextDue(p.getId()).orElse(null), p.isNotificationsEnabled(),
                p.getSpot() != null ? p.getSpot().getId() : null, SpaceManager.pathOf(p),
                health.isEmpty() ? null : health.get(0).getStatus(), p.getAddedAt());
    }

}
