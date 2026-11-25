package com.plantorganizer.agent;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;

import com.plantorganizer.agent.PendingChanges.PendingChange;
import com.plantorganizer.common.ExternalServiceException;
import com.plantorganizer.common.ForbiddenException;
import com.plantorganizer.common.InvalidInputException;
import com.plantorganizer.common.NotFoundException;
import com.plantorganizer.health.HealthReport;
import com.plantorganizer.identification.IdentificationResult;
import com.plantorganizer.identification.PlantCandidate;
import com.plantorganizer.identification.PlantImage;
import com.plantorganizer.memory.AgentMemory;
import com.plantorganizer.memory.ChatMessage;
import com.plantorganizer.memory.UserPreference;
import com.plantorganizer.notification.WateringOverview;
import com.plantorganizer.plantinfo.PlantCareProfile;
import com.plantorganizer.plantinfo.PlantQuery;
import com.plantorganizer.plants.DesiredPlant;
import com.plantorganizer.plants.HealthRecord;
import com.plantorganizer.plants.OwnedPlant;
import com.plantorganizer.plants.PlantNote;
import com.plantorganizer.plants.PlantRepository;
import com.plantorganizer.recommendation.RecommendationEngine;
import com.plantorganizer.recommendation.RecommendedPlant;
import com.plantorganizer.schedule.RescheduleResult;
import com.plantorganizer.schedule.ScheduleManager;
import com.plantorganizer.schedule.availability.AvailabilityInput;
import com.plantorganizer.schedule.availability.ScheduleParseException;
import com.plantorganizer.spaces.SpaceComponent;
import com.plantorganizer.spaces.SpaceManager;
import com.plantorganizer.spaces.SpaceView;
import com.plantorganizer.user.User;
import com.plantorganizer.user.UserRepository;
import com.plantorganizer.websearch.ResearchResult;

/**
 * The agent's conversational front end, behind the GUI's chat box (new class
 * in Stage 2). Stage 1's CliConsole is now the terminal Agent.jar, which
 * reaches the same agent through {@link #ask}.
 *
 * For each message it stores it in AgentMemory, asks AgentPlanner to
 * interpret the intent with the user's plants, spaces and recent conversation
 * as context, and resolves plant and space names against the database. Then:
 * <ul>
 *   <li>questions and analyses (care questions, identification, health
 *       checks, recommendations, fit checks, what needs water) are answered
 *       straight away with the same PlantAgent plans the GUI pages use;</li>
 *   <li>a message never changes the user's data by itself. A change (adding a
 *       plant, rescheduling, marking watered, moving a plant, editing notes,
 *       reminders, remembering a preference) is worked out but not made: it
 *       becomes a {@link PendingChanges proposal}, and the reply says exactly
 *       what will change, with a Yes button. Only pressing it makes the
 *       change.</li>
 * </ul>
 * Button clicks come back through {@link #act}.
 */
@Service
public class ChatAgent {

    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("EEE MMM d, HH:mm", Locale.ENGLISH);

    static final String NO_LONGER_WAITING = "That change isn't waiting any more: it was already made or cancelled, "
            + "or it expired (I keep proposed changes for 30 minutes). Ask me again if you still want it.";
    static final String NOTHING_CHANGED = "OK, I didn't change anything.";

    /** A bare "yes" or "no" typed in answer to a proposed change. */
    private static final Pattern YES = Pattern.compile(
            "(?i)(y|yes|yeah|yep|yup|sure|ok|okay|please|yes please|do it|go ahead|confirm|approve|sounds good)[.! ]*");
    private static final Pattern NO = Pattern.compile(
            "(?i)(n|no|nope|nah|no thanks|no thank you|cancel|don'?t|never ?mind|stop)[.! ]*");

    /**
     * The allow-list of the read-only chat (Agent.jar's /chat): intents that
     * only read data. Every other intent changes something, so read-only chat
     * answers it with a {@link SuggestedAction} instead of running it.
     */
    static final Set<ChatIntent.Type> READ_ONLY_INTENTS = EnumSet.of(
            ChatIntent.Type.GENERAL, ChatIntent.Type.CARE_QUESTION, ChatIntent.Type.WATERING_STATUS);

    private final PlantAgent agent;
    private final AgentPlanner planner;
    private final AgentMemory memory;
    private final PlantRepository plantRepository;
    private final SpaceManager spaceManager;
    private final RecommendationEngine recommendationEngine;
    private final UserRepository userRepository;
    private final ScheduleManager scheduleManager;
    private final PendingChanges pending;
    private final Clock clock;

    public ChatAgent(PlantAgent agent, AgentPlanner planner, AgentMemory memory, PlantRepository plantRepository,
                     SpaceManager spaceManager, RecommendationEngine recommendationEngine,
                     UserRepository userRepository, ScheduleManager scheduleManager, PendingChanges pending,
                     Clock clock) {
        this.agent = agent;
        this.planner = planner;
        this.memory = memory;
        this.plantRepository = plantRepository;
        this.spaceManager = spaceManager;
        this.recommendationEngine = recommendationEngine;
        this.userRepository = userRepository;
        this.scheduleManager = scheduleManager;
        this.pending = pending;
        this.clock = clock;
    }

    // ================================================================== messages

    public ChatReply chat(long userId, String message, PlantImage photo) {
        String text = message == null ? "" : message.trim();
        if (text.isEmpty() && photo == null) {
            throw new InvalidInputException("Type a message or attach a photo.");
        }
        userRepository.findById(userId);
        memory.remember(userId, ChatMessage.Role.USER, text.isEmpty() ? "[sent a photo]"
                : text + (photo != null ? " [photo attached]" : ""));
        ChatReply reply = photo == null ? answerToOffer(userId, text).orElse(null) : null;
        if (reply == null) {
            ChatIntent intent = planner.interpret(text.isEmpty() ? "(photo only, no text)" : text, photo != null,
                    context(userId));
            reply = safely(intent.type().name(), () -> handle(userId, intent, text, photo));
            if (intent.preference() != null && !intent.preference().isBlank()) {
                reply = offerToRemember(userId, reply, intent.preference().trim());
            }
        }
        pending.offered(userId, reply);
        memory.remember(userId, ChatMessage.Role.AGENT, reply.text());
        return reply;
    }

    /** A button under a reply was pressed. APPROVE makes a proposed change; DECLINE drops it. */
    public ChatReply act(long userId, String type, Map<String, Object> payload) {
        ChatReply reply = safely(type, () -> handleAction(userId, type, payload == null ? Map.of() : payload));
        pending.offered(userId, reply);
        memory.remember(userId, ChatMessage.Role.AGENT, reply.text());
        return reply;
    }

    /**
     * A bare "yes" or "no" typed right after the agent proposed a change.
     * Changes are approved with their button only, so a typed "yes" changes
     * nothing: the agent shows the buttons again. A typed "no" cancels what
     * was proposed. Anything else goes to the planner as usual.
     */
    private Optional<ChatReply> answerToOffer(long userId, String text) {
        boolean yes = YES.matcher(text).matches();
        boolean no = NO.matcher(text).matches();
        List<ChatAction> offer = yes || no ? pending.lastOffer(userId) : List.of();
        if (offer.isEmpty()) {
            return Optional.empty();
        }
        if (no) {
            offer.forEach(a -> pending.take(userId, PendingChanges.proposalId(a)));
            return Optional.of(ChatReply.of(NOTHING_CHANGED, "DECLINE"));
        }
        String intent = pending.peek(userId, PendingChanges.proposalId(offer.get(0)))
                .map(PendingChange::intent).orElse("APPROVE");
        return Optional.of(new ChatReply("Nothing has changed yet: I only make a change when you press its button. "
                + "Press it here to go ahead:", offer, intent, List.of()));
    }

    /**
     * The planner noticed a lasting fact about the user ("has a cat").
     * Remembering it changes what the agent knows about them (it steers
     * recommendations), so it is offered with a button too.
     */
    private ChatReply offerToRemember(long userId, ChatReply reply, String fact) {
        String label = "Remember: " + (fact.length() > 40 ? fact.substring(0, 37) + "..." : fact);
        PendingChange remember = pending.propose(userId, "REMEMBER", "Remember: " + fact, label, () -> {
            memory.savePreference(userId, UserPreference.Kind.NOTE, fact);
            return ChatReply.of("OK, I'll remember that: " + fact + ".", "REMEMBER");
        });
        List<ChatAction> actions = new ArrayList<>(reply.actions());
        actions.add(PendingChanges.button(label, "APPROVE", remember));
        return new ChatReply(reply.text() + "\n\nShould I remember this for next time? It helps me recommend plants: **"
                + fact + "**", actions, reply.intent(), reply.toolsUsed(), reply.suggestion());
    }

    /** A reply proposing a change: what will change, a Yes button that makes it, and a No button. */
    private ChatReply propose(long userId, String kind, String description, String approveLabel,
                              Supplier<ChatReply> change) {
        return propose(userId, kind, description, approveLabel, Map.of(), change);
    }

    private ChatReply propose(long userId, String kind, String description, String approveLabel,
                              Map<String, Object> approvePayload, Supplier<ChatReply> change) {
        PendingChange proposal = pending.propose(userId, kind, description, approveLabel, change);
        Map<String, Object> approve = new HashMap<>(approvePayload);
        approve.put(PendingChanges.PROPOSAL_ID, proposal.id());
        return new ChatReply(description, List.of(new ChatAction(approveLabel, "APPROVE", approve),
                PendingChanges.button("No, leave it", "DECLINE", proposal)), kind, List.of());
    }

    /**
     * Read-only chat for the terminal CLI (Agent.jar's /chat). Questions,
     * care questions and "what needs water?" are answered as usual; anything
     * that would change data is not run. The reply then carries a
     * {@link SuggestedAction} the CLI turns into a structured command the user
     * confirms. No preference is saved and the reply has no buttons, so
     * nothing said in this chat changes the user's data.
     */
    public ChatReply ask(long userId, String message) {
        String text = message == null ? "" : message.trim();
        if (text.isEmpty()) {
            throw new InvalidInputException("Type a message for the agent.");
        }
        userRepository.findById(userId);
        memory.remember(userId, ChatMessage.Role.USER, text);
        ChatIntent intent = planner.interpret(text, false, context(userId));
        ChatReply reply;
        if (READ_ONLY_INTENTS.contains(intent.type())) {
            reply = safely(intent.type().name(), () -> handle(userId, intent, text, null)).withoutActions();
        } else {
            reply = new ChatReply("That's a change to your data, and chat only answers questions. "
                    + "There's a command that can do it for you.", List.of(), intent.type().name(), List.of(),
                    SuggestedAction.of(intent));
        }
        memory.remember(userId, ChatMessage.Role.AGENT, reply.text());
        return reply;
    }

    /**
     * What does this text ask for? Runs only the planner: nothing is stored,
     * answered or changed. The CLI uses it when a typed command is malformed,
     * to offer the command it thinks the user meant.
     */
    public SuggestedAction interpret(long userId, String text) {
        if (text == null || text.isBlank()) {
            throw new InvalidInputException("Nothing to interpret.");
        }
        userRepository.findById(userId);
        return SuggestedAction.of(planner.interpret(text.trim(), false, context(userId)));
    }

    public List<ChatMessage> history(long userId) {
        return memory.history(userId);
    }

    public void clear(long userId) {
        memory.clearConversation(userId);
        pending.clear(userId);
    }

    private interface ReplySupplier {
        ChatReply get();
    }

    /** Problems become a chat answer instead of an error, so the conversation can continue (UC04 "ask again"). */
    private static ChatReply safely(String intent, ReplySupplier work) {
        try {
            return work.get();
        } catch (ScheduleParseException e) {
            return ChatReply.of(e.getMessage(), intent);
        } catch (ExternalServiceException e) {
            return ChatReply.of("I couldn't finish that: " + e.getMessage()
                    + (e.isRetryable() ? " Please try again in a moment." : ""), intent);
        } catch (InvalidInputException | NotFoundException | ForbiddenException e) {
            return ChatReply.of(e.getMessage(), intent);
        }
    }

    private ChatReply handle(long userId, ChatIntent intent, String text, PlantImage photo) {
        String details = firstNonBlank(intent.details(), text);
        String kind = intent.type().name();
        return switch (intent.type()) {
            case IDENTIFY -> identify(userId, photo, details, kind);
            case ADD_OWNED -> addOwnedByName(userId, firstNonBlank(intent.plantName(), details), intent.spaceName(), kind);
            case ADD_DESIRED -> addDesiredByName(userId, firstNonBlank(intent.plantName(), details), intent.spaceName(), kind);
            case HEALTH_CHECK -> health(userId, intent, details, photo, text, kind);
            case UPDATE_AVAILABILITY -> proposeAvailability(userId, details, photo, kind);
            case UNDO_SCHEDULE -> proposeUndo(userId, kind);
            case RECOMMEND -> recommend(userId, details, kind);
            case REFRESH_RECOMMENDATIONS -> {
                AgentResponse r = agent.run(AgentRequest.recommendLearned(userId));
                List<?> list = r.resultAs(List.class);
                yield reply("I refreshed your recommendations from what I've learned about you: "
                        + String.join(", ", list.stream().map(o -> ((RecommendedPlant) o).getDisplayName()).toList()) + ".",
                        kind, r, List.of(ChatAction.open("See recommendations", "recommendedplants.html")));
            }
            case PLACE_PLANT -> place(userId, intent, text, kind);
            case WATERING_STATUS -> wateringStatus(userId, kind);
            case MARK_WATERED -> {
                if (intent.plantName() != null && intent.plantName().toLowerCase(Locale.ROOT).matches("all|everything|all of them|all plants")) {
                    yield proposeWaterAllDue(userId, kind);
                }
                OwnedPlant plant = resolveOwned(userId, intent.plantName(), text);
                yield plant == null ? whichPlant(userId, "Which plant did you water?", "I watered my ", kind)
                        : proposeWatered(plant, userId, kind);
            }
            case NOTIFICATIONS_ON, NOTIFICATIONS_OFF -> {
                boolean on = intent.type() == ChatIntent.Type.NOTIFICATIONS_ON;
                OwnedPlant plant = resolveOwned(userId, intent.plantName(), null);
                yield plant != null && intent.plantName() != null ? proposeNotify(userId, plant, on, kind)
                        : proposeNotifyAll(userId, on, kind);
            }
            case CHECK_FIT -> checkFit(userId, intent, kind);
            case UPDATE_CARE_NOTES -> updateCareNotes(userId, intent, text, kind);
            case ADD_NOTE -> addNote(userId, intent, text, kind);
            case CARE_QUESTION, GENERAL -> {
                OwnedPlant plant = resolveOwned(userId, intent.plantName(), text);
                PlantQuery query = plant != null ? PlantQuery.of(plant.getScientificName(), plant.getCommonName())
                        : intent.type() == ChatIntent.Type.CARE_QUESTION && intent.plantName() != null
                          ? PlantQuery.of(intent.plantName(), null) : null;
                AgentResponse r = agent.run(AgentRequest.answerQuestion(userId, text, query, context(userId)));
                yield reply(r.resultAs(String.class), kind, r, List.of());
            }
        };
    }

    // ================================================================== button actions

    private ChatReply handleAction(long userId, String type, Map<String, Object> p) {
        return switch (type) {
            case "APPROVE" -> pending.take(userId, str(p, PendingChanges.PROPOSAL_ID))
                    .map(proposal -> proposal.change().get())
                    .orElseGet(() -> ChatReply.of(NO_LONGER_WAITING, type));
            case "DECLINE" -> pending.take(userId, str(p, PendingChanges.PROPOSAL_ID))
                    .map(proposal -> ChatReply.of(NOTHING_CHANGED, proposal.intent()))
                    .orElseGet(() -> ChatReply.of(NO_LONGER_WAITING, type));
            case "CONFIRM_CANDIDATE" -> {
                AgentResponse r = agent.run(AgentRequest.explainConfirmedPlant(userId,
                        PlantQuery.of(str(p, "scientificName"), str(p, "commonName"))));
                PlantReport report = r.resultAs(PlantReport.class);
                String name = report.profile().commonName() != null ? report.profile().commonName() : report.profile().scientificName();
                String text = (report.profile().similarPlantName() != null
                        ? "Heads up: I couldn't find care data for that exact species, so this describes a similar plant ("
                          + report.profile().similarPlantName() + ").\n\n" : "")
                        + report.explanation() + "\n\n_Source: " + report.profile().source().getLabel() + "_";
                Map<String, Object> plant = Map.of("scientificName", report.profile().scientificName(),
                        "commonName", name);
                yield reply(text, type, r, List.of(new ChatAction("I own this plant", "OWN_PLANT", plant),
                        new ChatAction("Add to wishlist", "ADD_DESIRED", plant)));
            }
            case "OWN_PLANT" -> addOwned(userId, str(p, "scientificName"), str(p, "commonName"), lng(p, "spaceId"), type);
            case "ADD_DESIRED" -> {
                AgentResponse r = agent.run(AgentRequest.addDesiredPlant(userId, str(p, "scientificName"),
                        str(p, "commonName"), lng(p, "spaceId"), null, str(p, "imageUrl")));
                DesiredPlant d = r.resultAs(DesiredPlant.class);
                yield reply("Added **" + d.getDisplayName() + "** to your wishlist. Pick the space you'd put it in on the "
                        + "wishlist page and I'll tell you whether it's a good spot.", type, r,
                        List.of(ChatAction.open("Open wishlist", "desiredplants.html")));
            }
            case "NOTIFY" -> notify(userId, lng(p, "plantId"), Boolean.TRUE.equals(p.get("enabled")),
                    Boolean.TRUE.equals(p.get("remember")), type);
            case "RESEARCH_WATERING" -> {
                OwnedPlant plant = plantRepository.findOwned(userId, lng(p, "plantId"));
                AgentResponse r = agent.run(AgentRequest.researchWatering(userId, plant.getId(),
                        plant.getScientificName(), plant.getDisplayName()));
                yield reply(r.resultAs(com.plantorganizer.schedule.ScheduleResult.class).explanation(), type, r,
                        List.of(new ChatAction("Undo", "UNDO_SCHEDULE", Map.of())));
            }
            case "UNDO_SCHEDULE" -> undo(userId, type);
            case "WATERED" -> water(userId, lng(p, "plantId"), type);
            case "SAVE_HEALTH_NOTE" -> {
                HealthRecord record = plantRepository.findHealthRecord(lng(p, "recordId"));
                OwnedPlant plant = plantRepository.findOwned(userId, record.getPlantId());
                plantRepository.saveNote(plant.getId(), PlantNote.Kind.HEALTH, healthNote(record));
                yield ChatReply.of("Saved to " + plant.getDisplayName() + "'s notes.", type);
            }
            case "SAVE_NOTE" -> {
                OwnedPlant plant = plantRepository.findOwned(userId, lng(p, "plantId"));
                plantRepository.saveNote(plant.getId(), PlantNote.Kind.AGENT, str(p, "text"));
                yield ChatReply.of("Saved to " + plant.getDisplayName() + "'s notes.", type);
            }
            case "HEALTH_MORE" -> healthMore(userId, lng(p, "recordId"), type);
            case "HEALTH_STILL_UNSATISFIED" -> {
                OwnedPlant plant = plantRepository.findOwned(userId, lng(p, "plantId"));
                yield new ChatReply("Sorry I couldn't pin it down. You know " + plant.getDisplayName()
                        + " best: add what you've noticed to its notes (recent watering, light changes, pests, new pot) "
                        + "so the next check has more to go on, and compare photos over the next week.",
                        List.of(ChatAction.open("Open its notes", "currentlistofplants.html?plant=" + plant.getId())),
                        type, List.of());
            }
            case "ACCEPT_REC" -> {
                RecommendedPlant rec = recommendationEngine.accept(userId, lng(p, "id"));
                yield new ChatReply("Added **" + rec.getDisplayName() + "** to your recommendations. I'll remember you like it.",
                        List.of(new ChatAction("Add to wishlist too", "ADD_DESIRED", Map.of(
                                "scientificName", rec.getScientificName(), "commonName", rec.getDisplayName()))),
                        type, List.of());
            }
            case "REJECT_REC" -> {
                RecommendedPlant rec = recommendationEngine.find(userId, lng(p, "id"));
                recommendationEngine.unrecommend(userId, rec.getId());
                yield ChatReply.of("Got it, I won't suggest " + rec.getDisplayName() + " again.", type);
            }
            case "CREATE_ROOM_AND_PLACE" -> {
                SpaceView room = spaceManager.createSpace(userId, str(p, "name"), null, null, false);
                yield placeIn(userId, lng(p, "plantId"), room.id(), type);
            }
            case "CHECK_FIT" -> {
                DesiredPlant desired = plantRepository.findDesiredById(userId, lng(p, "desiredId"));
                yield fitReply(userId, desired, type);
            }
            default -> throw new InvalidInputException("Unknown action " + type + ".");
        };
    }

    // ================================================================== intent handlers

    private ChatReply identify(long userId, PlantImage photo, String description, String kind) {
        AgentResponse r = photo != null ? agent.run(AgentRequest.identifyFromPhotos(userId, List.of(photo)))
                : agent.run(AgentRequest.identifyFromText(userId, description));
        IdentificationResult result = r.resultAs(IdentificationResult.class);
        if (result.noMatch()) {
            return reply("I couldn't recognise a plant from that. " + (photo != null
                    ? "Try a clear close-up of a leaf or flower, or describe it to me."
                    : "Tell me more: leaf shape and size, colour, flowers, and how big it is.")
                    + notesSuffix(result.notes()), kind, r, List.of());
        }
        StringBuilder sb = new StringBuilder("Here are my best guesses:\n");
        List<ChatAction> actions = new ArrayList<>();
        for (PlantCandidate c : result.candidates()) {
            String name = c.primaryCommonName() != null ? c.primaryCommonName() : c.scientificName();
            sb.append("- **").append(name).append("** (").append(c.scientificName()).append(")")
              .append(c.score() > 0 ? " - " + Math.round(c.score() * 100) + "% confidence" : "").append('\n');
            actions.add(new ChatAction("It's " + name, "CONFIRM_CANDIDATE",
                    Map.of("scientificName", c.scientificName(), "commonName", name)));
        }
        sb.append("\nCompare them with your plant and pick one, or send another photo if none match.");
        return reply(sb + notesSuffix(result.notes()), kind, r, actions);
    }

    private ChatReply addOwnedByName(long userId, String name, String spaceName, String kind) {
        if (name == null || name.isBlank()) {
            return ChatReply.of("Which plant did you get? Tell me its name or describe it.", kind);
        }
        Optional<PlantCandidate> match = bestMatch(userId, name);
        Optional<SpaceComponent> space = spaceManager.findByName(userId, spaceName);
        Long spaceId = space.map(SpaceComponent::getId).orElse(null);
        if (match.isEmpty()) {
            return candidatesToPick(userId, name, "OWN_PLANT", "Add ", spaceId, kind);
        }
        PlantCandidate c = match.get();
        String commonName = firstNonBlank(c.primaryCommonName(), name);
        return propose(userId, kind, "Add **" + commonName + "** (" + c.scientificName() + ") to your plants"
                        + space.map(s -> ", in " + s.path()).orElse("") + "?", "Yes, add it",
                () -> addOwned(userId, c.scientificName(), commonName, spaceId, kind));
    }

    private ChatReply addDesiredByName(long userId, String name, String spaceName, String kind) {
        if (name == null || name.isBlank()) {
            return ChatReply.of("Which plant would you like on your wishlist?", kind);
        }
        Optional<PlantCandidate> match = bestMatch(userId, name);
        Optional<SpaceComponent> space = spaceManager.findByName(userId, spaceName);
        Long spaceId = space.map(SpaceComponent::getId).orElse(null);
        if (match.isEmpty()) {
            return candidatesToPick(userId, name, "ADD_DESIRED", "Wishlist ", spaceId, kind);
        }
        PlantCandidate c = match.get();
        String commonName = firstNonBlank(c.primaryCommonName(), name);
        return propose(userId, kind, "Add **" + commonName + "** (" + c.scientificName() + ") to your wishlist"
                + space.map(s -> ", for " + s.path()).orElse("") + "?", "Yes, add it", () -> {
            AgentResponse r = agent.run(AgentRequest.addDesiredPlant(userId, c.scientificName(), commonName, spaceId,
                    null, c.referenceImageUrl()));
            DesiredPlant d = r.resultAs(DesiredPlant.class);
            List<ChatAction> actions = new ArrayList<>(List.of(ChatAction.open("Open wishlist", "desiredplants.html")));
            if (spaceId != null) {
                actions.add(0, new ChatAction("Is that a good spot?", "CHECK_FIT", Map.of("desiredId", d.getId())));
            }
            return reply("Added **" + d.getDisplayName() + "** (" + d.getScientificName() + ") to your wishlist.", kind,
                    r, actions);
        });
    }

    /** Uses the text-identification tool to turn a plant name into a verified species. */
    private Optional<PlantCandidate> bestMatch(long userId, String name) {
        IdentificationResult result = agent.run(AgentRequest.identifyFromText(userId, name)).resultAs(IdentificationResult.class);
        String n = name.toLowerCase(Locale.ROOT);
        return result.candidates().stream()
                .filter(c -> c.scientificName().toLowerCase(Locale.ROOT).contains(n) || n.contains(c.scientificName().toLowerCase(Locale.ROOT))
                        || c.commonNames().stream().anyMatch(cn -> cn.toLowerCase(Locale.ROOT).contains(n) || n.contains(cn.toLowerCase(Locale.ROOT))))
                .findFirst()
                .or(() -> result.candidates().size() == 1 ? Optional.of(result.candidates().get(0)) : Optional.empty());
    }

    private ChatReply candidatesToPick(long userId, String name, String actionType, String labelPrefix, Long spaceId,
                                       String kind) {
        AgentResponse r = agent.run(AgentRequest.identifyFromText(userId, name));
        IdentificationResult result = r.resultAs(IdentificationResult.class);
        if (result.noMatch()) {
            return reply("I couldn't find a plant called \"" + name + "\". Could you give its full name or describe it?",
                    kind, r, List.of());
        }
        List<ChatAction> actions = new ArrayList<>();
        for (PlantCandidate c : result.candidates()) {
            String label = c.primaryCommonName() != null ? c.primaryCommonName() : c.scientificName();
            Map<String, Object> payload = new java.util.HashMap<>(Map.of("scientificName", c.scientificName(), "commonName", label));
            if (spaceId != null) {
                payload.put("spaceId", spaceId);
            }
            actions.add(new ChatAction(labelPrefix + label, actionType, payload));
        }
        return reply("Which of these is it?", kind, r, actions);
    }

    private ChatReply addOwned(long userId, String scientificName, String commonName, Long spaceId, String kind) {
        AgentResponse r = agent.run(AgentRequest.addOwnedPlant(userId, PlantQuery.of(scientificName, commonName), null, spaceId));
        AddPlantResult added = r.resultAs(AddPlantResult.class);
        StringBuilder sb = new StringBuilder("Added **" + added.plantName() + "** to your plants")
                .append(added.location() != null ? " (" + added.location() + ")" : "").append(".\n\n")
                .append(added.schedule().explanation());
        List<ChatAction> actions = new ArrayList<>();
        if (added.schedule().askAboutNotifications()) {
            sb.append("\n\nWould you like me to remind you when your plants need water?");
            actions.add(new ChatAction("Yes, remind me", "NOTIFY", Map.of("plantId", added.plantId(), "enabled", true, "remember", true)));
            actions.add(new ChatAction("No reminders", "NOTIFY", Map.of("plantId", added.plantId(), "enabled", false, "remember", true)));
        }
        if (added.schedule().dataWarning() != null) {
            actions.add(new ChatAction("Search for more info", "RESEARCH_WATERING", Map.of("plantId", added.plantId())));
        }
        if (added.location() == null) {
            sb.append("\n\nTell me where it lives (e.g. \"it's on the bedroom window sill\") and I'll add it to your spaces.");
        }
        actions.add(ChatAction.open("Open my plants", "currentlistofplants.html?plant=" + added.plantId()));
        return reply(sb + notesSuffix(added.notes()), kind, r, actions);
    }

    private ChatReply health(long userId, ChatIntent intent, String details, PlantImage photo, String text, String kind) {
        OwnedPlant plant = resolveOwned(userId, intent.plantName(), text);
        if (plant == null) {
            return whichPlant(userId, "Which of your plants is this about?"
                    + (photo != null ? " Mention its name and attach the photo again." : ""), "Check the health of my ", kind);
        }
        AgentResponse r = agent.run(AgentRequest.analyzeHealth(userId, plant.getId(), photo,
                text.isEmpty() ? null : details, photo != null));
        HealthReport report = r.resultAs(HealthReport.class);
        HealthRecord rec = report.record();
        StringBuilder sb = new StringBuilder("**").append(report.plantName()).append("**: ")
                .append(rec.getStatus().name().replace('_', ' ').toLowerCase(Locale.ROOT)).append(" (health ")
                .append(rec.getHealthScore()).append("/10")
                .append(rec.getTrend() != HealthRecord.Trend.FIRST_CHECK ? ", " + rec.getTrend().name().toLowerCase(Locale.ROOT) + " since last check" : "")
                .append(")\n\n");
        if (report.speciesWarning() != null) {
            sb.append("⚠️ ").append(report.speciesWarning()).append("\n\n");
        }
        sb.append(rec.getSummary()).append("\n\n**What I see:**\n").append(bullets(rec.getIssues()))
          .append("\n\n**What to do:**\n").append(bullets(rec.getCareSteps()));
        if (rec.getComparison() != null && !rec.getComparison().isBlank()) {
            sb.append("\n\n**Compared with last time:** ").append(rec.getComparison());
        }
        return reply(sb + notesSuffix(report.notes()), kind, r, List.of(
                new ChatAction("Save to plant notes", "SAVE_HEALTH_NOTE", Map.of("recordId", rec.getId())),
                new ChatAction("Not satisfied, dig deeper", "HEALTH_MORE", Map.of("recordId", rec.getId()))));
    }

    private ChatReply healthMore(long userId, long recordId, String kind) {
        HealthRecord record = plantRepository.findHealthRecord(recordId);
        OwnedPlant plant = plantRepository.findOwned(userId, record.getPlantId());
        AgentResponse r = agent.run(AgentRequest.researchHealth(userId, plant.getScientificName(),
                "My " + plant.getDisplayName() + " (" + plant.getScientificName() + ") shows: " + oneLine(record.getIssues())
                        + ". The advice so far was: " + oneLine(record.getCareSteps())
                        + ". That didn't fully help. What else could cause this, and what should I check or try?",
                "Owner's description: " + (record.getUserDescription() != null ? record.getUserDescription() : "none")));
        ResearchResult research = r.resultAs(ResearchResult.class);
        StringBuilder sb = new StringBuilder(research.answer());
        if (!research.sources().isEmpty()) {
            sb.append("\n\nSources:");
            for (int i = 0; i < research.sources().size(); i++) {
                sb.append("\n- [").append(i + 1).append("] ").append(research.sources().get(i).title());
            }
        }
        return reply(sb.toString(), kind, r, List.of(
                new ChatAction("Save to plant notes", "SAVE_NOTE", Map.of("plantId", plant.getId(), "text", research.answer())),
                new ChatAction("Still not helpful", "HEALTH_STILL_UNSATISFIED", Map.of("plantId", plant.getId()))));
    }

    /** F03 from the chat box: the availability (typed, or a timetable photo) is applied only once approved. */
    private ChatReply proposeAvailability(long userId, String details, PlantImage photo, String kind) {
        AvailabilityInput input = photo != null ? AvailabilityInput.file(photo) : AvailabilityInput.text(details);
        String what = photo != null ? "Read the timetable in your photo and re-plan your waterings to fit it?"
                : "Re-plan your waterings to fit this availability?\n_\"" + details + "\"_";
        return propose(userId, kind, what + "\n\nYou can undo it afterwards.", "Yes, update my schedule", () -> {
            AgentResponse r = agent.run(AgentRequest.updateSchedule(userId, input));
            RescheduleResult result = r.resultAs(RescheduleResult.class);
            return reply(result.explanation(), kind, r, List.of(
                    new ChatAction("Undo", "UNDO_SCHEDULE", Map.of()), ChatAction.open("Open calendar", "calendar.html")));
        });
    }

    private ChatReply proposeUndo(long userId, String kind) {
        Optional<String> last = scheduleManager.lastChange(userId);
        if (last.isEmpty()) {
            return ChatReply.of("There's no schedule change to undo.", kind);
        }
        String change = last.get();
        return propose(userId, kind, "Undo your last schedule change?\n_" + change + "_", "Yes, undo it",
                () -> scheduleManager.lastChange(userId).filter(change::equals).isPresent() ? undo(userId, kind)
                        : ChatReply.of("Your last schedule change has changed since I asked, so I didn't undo anything. "
                                + "Ask me again if you still want to undo it.", kind));
    }

    private ChatReply undo(long userId, String kind) {
        AgentResponse r = agent.run(AgentRequest.undoSchedule(userId));
        Optional<?> undone = r.resultAs(Optional.class);
        return reply(undone.map(d -> "Undone: " + d + ".").orElse("There's no schedule change to undo."), kind, r,
                List.of(ChatAction.open("Open calendar", "calendar.html")));
    }

    private ChatReply recommend(long userId, String constraints, String kind) {
        AgentResponse r = agent.run(AgentRequest.recommendFor(userId, constraints));
        List<?> list = r.resultAs(List.class);
        if (list.isEmpty()) {
            return reply("None of the plants I considered met all of that. Try relaxing one constraint, for example "
                    + "allowing medium care or a brighter spot, and ask again.", kind, r, List.of());
        }
        StringBuilder sb = new StringBuilder("Here's what I'd suggest:\n");
        List<ChatAction> actions = new ArrayList<>();
        for (Object o : list) {
            RecommendedPlant rec = (RecommendedPlant) o;
            sb.append("- **").append(rec.getDisplayName()).append("** (").append(rec.getScientificName()).append(", ")
              .append(rec.getCareLevel() != null ? rec.getCareLevel().toLowerCase(Locale.ROOT) + " care" : "care level unknown")
              .append(rec.isVerified() ? "" : ", not verified in Perenual").append("): ").append(rec.getReason()).append('\n');
            actions.add(new ChatAction("Keep " + rec.getDisplayName(), "ACCEPT_REC", Map.of("id", rec.getId())));
            actions.add(new ChatAction("Not for me: " + rec.getDisplayName(), "REJECT_REC", Map.of("id", rec.getId())));
        }
        sb.append("\nKeep the ones you like and they'll join your recommendations list; I'll remember the ones you don't.");
        return reply(sb.toString(), kind, r, actions);
    }

    private ChatReply place(long userId, ChatIntent intent, String text, String kind) {
        OwnedPlant plant = resolveOwned(userId, intent.plantName(), text);
        if (plant == null) {
            return whichPlant(userId, "Which plant are you placing?", "Put my ", kind);
        }
        if (intent.spaceName() == null) {
            return ChatReply.of("Where is " + plant.getDisplayName() + "? Name the room or spot, e.g. \"the kitchen window\".", kind);
        }
        Optional<SpaceComponent> space = spaceManager.findByName(userId, intent.spaceName());
        if (space.isEmpty()) {
            return new ChatReply("I couldn't find a space called \"" + intent.spaceName() + "\" in your Room Builder.",
                    List.of(new ChatAction("Create \"" + intent.spaceName() + "\" and put it there", "CREATE_ROOM_AND_PLACE",
                                    Map.of("name", intent.spaceName(), "plantId", plant.getId())),
                            ChatAction.open("Open Room Builder", "spaces.html")), kind, List.of());
        }
        SpaceComponent target = space.get();
        if (plant.getSpot() != null && Objects.equals(plant.getSpot().getId(), target.getId())) {
            return ChatReply.of(plant.getDisplayName() + " is already in " + target.path() + ".", kind);
        }
        long plantId = plant.getId();
        long spaceId = target.getId();
        boolean moving = plant.getSpot() != null;
        return propose(userId, kind, "Put **" + plant.getDisplayName() + "** in " + target.path() + "?"
                        + (moving ? " It's in " + plant.getSpot().path() + " now." : ""),
                moving ? "Yes, move it" : "Yes, put it there", () -> placeIn(userId, plantId, spaceId, kind));
    }

    private ChatReply placeIn(long userId, long plantId, long spaceId, String kind) {
        AgentResponse r = agent.run(AgentRequest.placePlant(userId, plantId, spaceId));
        SpaceView spot = r.resultAs(SpaceView.class);
        OwnedPlant plant = plantRepository.findById(plantId);
        return reply("Done: **" + plant.getDisplayName() + "** is now in " + spot.path()
                + (spot.effectiveLight() != null ? " (" + spot.effectiveLight().toLowerCase(Locale.ROOT) + ")" : "") + ".",
                kind, r, List.of(ChatAction.open("Open Room Builder", "spaces.html")));
    }

    private ChatReply wateringStatus(long userId, String kind) {
        AgentResponse r = agent.run(AgentRequest.wateringOverview(userId));
        WateringOverview o = r.resultAs(WateringOverview.class);
        if (o.dueNow().isEmpty() && o.overdue().isEmpty() && o.upcoming().isEmpty()) {
            return reply("Nothing is scheduled. Add a plant and I'll plan its waterings.", kind, r, List.of());
        }
        StringBuilder sb = new StringBuilder();
        List<ChatAction> actions = new ArrayList<>();
        section(sb, "Missed (water as soon as you can)", o.overdue(), actions);
        section(sb, "Due now", o.dueNow(), actions);
        if (!o.upcoming().isEmpty()) {
            sb.append("**Coming up:**\n");
            o.upcoming().forEach(i -> sb.append("- ").append(i.plantName()).append(": ").append(i.dueAt().format(WHEN)).append('\n'));
        }
        return reply(sb.toString().trim(), kind, r, actions);
    }

    private static void section(StringBuilder sb, String title, List<WateringOverview.Item> items, List<ChatAction> actions) {
        if (items.isEmpty()) {
            return;
        }
        sb.append("**").append(title).append(":**\n");
        for (WateringOverview.Item i : items) {
            sb.append("- ").append(i.plantName()).append(" (planned ").append(i.dueAt().format(WHEN)).append(")\n");
            actions.add(new ChatAction("Watered " + i.plantName(), "WATERED", Map.of("plantId", i.plantId())));
        }
        sb.append('\n');
    }

    private ChatReply proposeWatered(OwnedPlant plant, long userId, String kind) {
        long plantId = plant.getId();
        return propose(userId, kind, "Mark **" + plant.getDisplayName() + "** as watered now?", "Yes, mark it watered",
                () -> water(userId, plantId, kind));
    }

    private ChatReply water(long userId, long plantId, String kind) {
        AgentResponse r = agent.run(AgentRequest.markWatered(userId, plantId));
        return reply(r.resultAs(String.class), kind, r, List.of());
    }

    private ChatReply proposeWaterAllDue(long userId, String kind) {
        WateringOverview o = agent.run(AgentRequest.wateringOverview(userId)).resultAs(WateringOverview.class);
        List<WateringOverview.Item> due = concat(o.overdue(), o.dueNow());
        if (due.isEmpty()) {
            return ChatReply.of("Nothing is due right now, so there's nothing to mark as watered.", kind);
        }
        StringBuilder sb = new StringBuilder("Mark these plants as watered now?\n");
        due.forEach(i -> sb.append("- ").append(i.plantName()).append('\n'));
        return propose(userId, kind, sb.toString().trim(), "Yes, mark them watered", () -> {
            List<String> names = new ArrayList<>();
            for (WateringOverview.Item i : due) {
                agent.run(AgentRequest.markWatered(userId, i.plantId()));
                names.add(i.plantName());
            }
            return ChatReply.of("Marked as watered: " + String.join(", ", names) + ".", kind);
        });
    }

    private ChatReply proposeNotify(long userId, OwnedPlant plant, boolean on, String kind) {
        long plantId = plant.getId();
        return propose(userId, kind, "Turn watering reminders " + (on ? "on" : "off") + " for **"
                        + plant.getDisplayName() + "**?", on ? "Yes, turn them on" : "Yes, turn them off",
                remindersPayload(on), () -> notify(userId, plantId, on, false, kind));
    }

    private ChatReply proposeNotifyAll(long userId, boolean on, String kind) {
        int count = plantRepository.findAll(userId).size();
        String which = count == 0 ? "the plants you add from now on" : yourPlants(count) + ", and for plants you add later";
        return propose(userId, kind, "Turn watering reminders " + (on ? "on" : "off") + " for " + which + "?",
                on ? "Yes, turn them on" : "Yes, turn them off", remindersPayload(on),
                () -> notifyAll(userId, on, kind));
    }

    /** Lets the chat box ask the browser for permission to show reminders when the user approves turning them on. */
    private static Map<String, Object> remindersPayload(boolean on) {
        return on ? Map.of("enablesReminders", true) : Map.of();
    }

    private ChatReply notify(long userId, long plantId, boolean enabled, boolean rememberDefault, String kind) {
        AgentResponse r = agent.run(AgentRequest.setNotifications(userId, plantId, enabled));
        OwnedPlant plant = plantRepository.findById(plantId);
        if (rememberDefault) {
            User user = userRepository.findById(userId);
            user.setNotificationsAsked(true);
            user.setNotifyByDefault(enabled);
            userRepository.save(user);
        }
        String text = enabled
                ? "Reminders are on for " + plant.getDisplayName() + ". I'll tell you when it's due and if a watering is missed"
                  + (rememberDefault ? ", and new plants will get reminders too (you can change this per plant)." : ".")
                : "OK, no reminders for " + plant.getDisplayName() + ". You can turn them on any time from My Plants.";
        return reply(text, kind, r, List.of());
    }

    private ChatReply notifyAll(long userId, boolean enabled, String kind) {
        List<OwnedPlant> plants = plantRepository.findAll(userId);
        for (OwnedPlant plant : plants) {
            agent.run(AgentRequest.setNotifications(userId, plant.getId(), enabled));
        }
        User user = userRepository.findById(userId);
        user.setNotificationsAsked(true);
        user.setNotifyByDefault(enabled);
        userRepository.save(user);
        String scope = plants.isEmpty() ? "the plants you add from now on"
                : yourPlants(plants.size()) + (enabled ? ", and for plants you add later" : "");
        return ChatReply.of((enabled ? "Reminders are on" : "Reminders are off") + " for " + scope + ".", kind);
    }

    private static String yourPlants(int count) {
        return count == 1 ? "your plant" : "all " + count + " of your plants";
    }

    private ChatReply checkFit(long userId, ChatIntent intent, String kind) {
        List<DesiredPlant> wishlist = plantRepository.findDesired(userId);
        DesiredPlant desired = matchByName(wishlist, intent.plantName(), DesiredPlant::getDisplayName, DesiredPlant::getScientificName)
                .orElse(wishlist.size() == 1 ? wishlist.get(0) : null);
        if (desired == null) {
            return ChatReply.of(wishlist.isEmpty() ? "Your wishlist is empty. Ask me to add a plant to it first."
                    : "Which wishlist plant do you mean? You have: " + String.join(", ",
                            wishlist.stream().map(DesiredPlant::getDisplayName).toList()) + ".", kind);
        }
        Optional<SpaceComponent> named = spaceManager.findByName(userId, intent.spaceName());
        if (named.isEmpty() || Objects.equals(named.get().getId(), desired.getIntendedSpaceId())) {
            return fitReply(userId, desired, kind);
        }
        // A different space than the one the user picked: check it without saving it, and offer to plan it there.
        SpaceComponent space = named.get();
        DesiredFit fit = DesiredFitRequests.evaluate(agent, userId, desired, space);
        long desiredId = desired.getId();
        long spaceId = space.getId();
        String path = space.path();
        PendingChange plan = pending.propose(userId, kind, "Plan to put " + desired.getDisplayName() + " in " + path,
                "Yes, plan it there", () -> {
                    DesiredPlant d = plantRepository.findDesiredById(userId, desiredId);
                    d.setIntendedSpaceId(spaceId);
                    d.recordFit(fit.verdict(), fit.explanation(), LocalDateTime.now(clock));
                    plantRepository.saveDesired(d);
                    return new ChatReply("Noted: you'd put **" + d.getDisplayName() + "** in " + path + ".",
                            List.of(ChatAction.open("Open wishlist", "desiredplants.html")), kind, List.of());
                });
        return new ChatReply(fitText(fit, path) + "\n\nShould I note " + path + " as where you'd put "
                + desired.getDisplayName() + "?",
                List.of(PendingChanges.button(plan.approveLabel(), "APPROVE", plan),
                        ChatAction.open("Open wishlist", "desiredplants.html")),
                kind, List.of("LOOKUP_PLANT_INFO"));
    }

    /** The fit for the space the user already picked: checked and recorded, as on the wishlist page. */
    private ChatReply fitReply(long userId, DesiredPlant desired, String kind) {
        if (desired.getIntendedSpaceId() == null) {
            return ChatReply.of("Which space are you thinking of for " + desired.getDisplayName() + "?", kind);
        }
        SpaceComponent space = spaceManager.getSpace(userId, desired.getIntendedSpaceId());
        DesiredFit fit = DesiredFitRequests.check(agent, plantRepository, userId, desired, space, LocalDateTime.now(clock));
        return new ChatReply(fitText(fit, space.path()),
                List.of(ChatAction.open("Open wishlist", "desiredplants.html")), kind, List.of("LOOKUP_PLANT_INFO"));
    }

    private static String fitText(DesiredFit fit, String path) {
        return "**" + fit.verdict().name().charAt(0) + fit.verdict().name().substring(1).toLowerCase(Locale.ROOT)
                + " fit** for " + path + ": " + fit.explanation();
    }

    /**
     * F08 from the chat box: works out which care-note fields the message
     * changes and proposes them, each with its current value; the other
     * fields are kept.
     */
    private ChatReply updateCareNotes(long userId, ChatIntent intent, String text, String kind) {
        OwnedPlant plant = resolveOwned(userId, intent.plantName(), text);
        if (plant == null) {
            return whichPlant(userId, "Which plant's care notes should I update?", "Update the care notes for my ", kind);
        }
        PlantCareProfile now = plant.getCare().toProfile(plant.getScientificName(), plant.getCommonName());
        Map<String, String> edits = planner.extractCareEdits(text, plant.getDisplayName(), describeCare(now), context(userId));
        if (edits.isEmpty()) {
            return new ChatReply("What should I change in " + plant.getDisplayName() + "'s care notes? Tell me the new "
                    + "value, e.g. \"set " + plant.getDisplayName() + "'s watering frequency to every 10 days\" or \"it needs "
                    + "bright indirect light\".",
                    List.of(ChatAction.open("Open its care notes", "currentlistofplants.html?plant=" + plant.getId())),
                    kind, List.of());
        }
        StringBuilder sb = new StringBuilder("Change **" + plant.getDisplayName() + "**'s care notes?\n");
        edits.forEach((field, value) -> {
            String current = currentValue(now, field);
            sb.append("- ").append(CARE_LABELS.getOrDefault(field, field)).append(": ")
              .append(AgentPlanner.CLEAR.equalsIgnoreCase(value) ? "(clear it)" : value)
              .append(current == null ? " (now empty)"
                      : " (now: " + (current.length() > 60 ? current.substring(0, 57) + "..." : current) + ")")
              .append('\n');
        });
        if (edits.containsKey("watering") || edits.containsKey("wateringFrequency")) {
            sb.append("\nI'll re-plan its waterings to match.");
        }
        long plantId = plant.getId();
        Map<String, String> chosen = new LinkedHashMap<>(edits);
        return propose(userId, kind, sb.toString().trim(), "Yes, save the changes",
                () -> applyCareEdits(userId, plantId, chosen, kind));
    }

    private ChatReply applyCareEdits(long userId, long plantId, Map<String, String> edits, String kind) {
        OwnedPlant plant = plantRepository.findOwned(userId, plantId);
        PlantCareProfile now = plant.getCare().toProfile(plant.getScientificName(), plant.getCommonName());
        plant.getCare().applyEdits(new PlantCareProfile(now.scientificName(), now.commonName(),
                edited(edits, "family", now.family()), edited(edits, "description", now.description()),
                edited(edits, "watering", now.watering()), edited(edits, "wateringFrequency", now.wateringFrequency()),
                editedSunlight(edits, now.sunlight()), edited(edits, "careLevel", now.careLevel()),
                edited(edits, "growthRate", now.growthRate()), editedFlag(edits, "indoor", now.indoor()),
                editedFlag(edits, "poisonousToPets", now.poisonousToPets()),
                editedFlag(edits, "poisonousToHumans", now.poisonousToHumans()),
                edited(edits, "hardinessZones", now.hardinessZones()), now.imageUrl(), now.source(), now.similarPlantName()));
        plantRepository.save(plant);

        StringBuilder sb = new StringBuilder("Updated **" + plant.getDisplayName() + "**'s care notes:\n");
        edits.forEach((field, value) -> sb.append("- ").append(CARE_LABELS.getOrDefault(field, field)).append(": ")
                .append(AgentPlanner.CLEAR.equalsIgnoreCase(value) ? "(cleared)" : value).append('\n'));
        List<ChatAction> actions = new ArrayList<>();
        List<String> toolsUsed = List.of();
        if (edits.containsKey("watering") || edits.containsKey("wateringFrequency")) {
            // The watering interval comes from these two fields, so the calendar has to follow them.
            try {
                AgentResponse r = agent.run(AgentRequest.generateSchedule(userId, plant.getId()));
                sb.append('\n').append(r.resultAs(com.plantorganizer.schedule.ScheduleResult.class).explanation());
                actions.add(new ChatAction("Undo schedule change", "UNDO_SCHEDULE", Map.of()));
                toolsUsed = r.toolsUsed();
            } catch (ExternalServiceException e) {
                sb.append("\nThe notes are saved, but I couldn't re-plan the waterings: ").append(e.getMessage());
            }
        }
        actions.add(ChatAction.open("Open its care notes", "currentlistofplants.html?plant=" + plant.getId()));
        return new ChatReply(sb.toString().trim(), actions, kind, toolsUsed);
    }

    /** Saves a free-text note the user dictated to the plant's Notes tab. */
    private ChatReply addNote(long userId, ChatIntent intent, String text, String kind) {
        OwnedPlant plant = resolveOwned(userId, intent.plantName(), text);
        if (plant == null) {
            return whichPlant(userId, "Which plant is the note for?", "Add that note to my ", kind);
        }
        String note = firstNonBlank(intent.details(), text);
        if (note == null || note.isBlank()) {
            return ChatReply.of("What should the note for " + plant.getDisplayName() + " say?", kind);
        }
        String clean = note.trim();
        long plantId = plant.getId();
        return propose(userId, kind, "Add this note to **" + plant.getDisplayName() + "**?\n_\"" + clean + "\"_",
                "Yes, add the note", () -> {
                    OwnedPlant owned = plantRepository.findOwned(userId, plantId);
                    plantRepository.saveNote(owned.getId(), PlantNote.Kind.USER, clean);
                    return new ChatReply("Saved to " + owned.getDisplayName() + "'s notes: \"" + clean + "\"",
                            List.of(ChatAction.open("Open its notes", "currentlistofplants.html?plant=" + owned.getId())),
                            kind, List.of());
                });
    }

    private static final Map<String, String> CARE_LABELS = Map.ofEntries(
            Map.entry("watering", "Watering"), Map.entry("wateringFrequency", "Watering frequency"),
            Map.entry("sunlight", "Sunlight"), Map.entry("careLevel", "Care level"),
            Map.entry("growthRate", "Growth rate"), Map.entry("hardinessZones", "Hardiness zones"),
            Map.entry("family", "Family"), Map.entry("description", "Description"), Map.entry("indoor", "Indoor"),
            Map.entry("poisonousToPets", "Poisonous to pets"), Map.entry("poisonousToHumans", "Poisonous to humans"));

    /** The current value of a care-note field, as text; null when it is empty. */
    private static String currentValue(PlantCareProfile c, String field) {
        String value = switch (field) {
            case "watering" -> c.watering();
            case "wateringFrequency" -> c.wateringFrequency();
            case "sunlight" -> c.sunlight().isEmpty() ? null : String.join(", ", c.sunlight());
            case "careLevel" -> c.careLevel();
            case "growthRate" -> c.growthRate();
            case "hardinessZones" -> c.hardinessZones();
            case "family" -> c.family();
            case "description" -> c.description();
            case "indoor" -> c.indoor() == null ? null : c.indoor().toString();
            case "poisonousToPets" -> c.poisonousToPets() == null ? null : c.poisonousToPets().toString();
            case "poisonousToHumans" -> c.poisonousToHumans() == null ? null : c.poisonousToHumans().toString();
            default -> null;
        };
        return value == null || value.isBlank() ? null : value;
    }

    private static String describeCare(PlantCareProfile c) {
        return "watering=" + c.watering() + "; wateringFrequency=" + c.wateringFrequency() + "; sunlight=" + c.sunlight()
                + "; careLevel=" + c.careLevel() + "; growthRate=" + c.growthRate() + "; hardinessZones=" + c.hardinessZones()
                + "; family=" + c.family() + "; indoor=" + c.indoor() + "; poisonousToPets=" + c.poisonousToPets()
                + "; poisonousToHumans=" + c.poisonousToHumans() + "; description=" + c.description();
    }

    /** The edited value of a field, the current one when it was not edited, null when it was cleared. */
    private static String edited(Map<String, String> edits, String field, String current) {
        String value = edits.get(field);
        return value == null ? current : AgentPlanner.CLEAR.equalsIgnoreCase(value) ? null : value;
    }

    private static List<String> editedSunlight(Map<String, String> edits, List<String> current) {
        String value = edits.get("sunlight");
        if (value == null) {
            return current;
        }
        return AgentPlanner.CLEAR.equalsIgnoreCase(value) ? List.of()
                : java.util.Arrays.stream(value.split("[,;]")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    private static Boolean editedFlag(Map<String, String> edits, String field, Boolean current) {
        String value = edits.get(field);
        if (value == null) {
            return current;
        }
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "true", "yes" -> Boolean.TRUE;
            case "false", "no" -> Boolean.FALSE;
            default -> null;
        };
    }

    // ================================================================== helpers

    /** Context for the planner and for answers: today's date, plants, spaces, wishlist and recent messages. */
    String context(long userId) {
        StringBuilder sb = new StringBuilder("Today is ").append(LocalDate.now(clock)).append(".\n");
        List<OwnedPlant> plants = plantRepository.findAll(userId);
        sb.append("User's plants: ").append(plants.isEmpty() ? "none" : String.join("; ", plants.stream()
                .map(p -> p.getDisplayName() + " (" + p.getScientificName() + ")"
                        + (p.getSpot() != null ? " in " + p.getSpot().path() : "") + ", watering " + p.getWateringStatus())
                .toList())).append('\n');
        List<String> spaces = spaceManager.describeSpaces(userId);
        sb.append("User's spaces: ").append(spaces.isEmpty() ? "none" : String.join("; ", spaces)).append('\n');
        List<DesiredPlant> wishlist = plantRepository.findDesired(userId);
        if (!wishlist.isEmpty()) {
            sb.append("Wishlist: ").append(String.join("; ", wishlist.stream().map(DesiredPlant::getDisplayName).toList())).append('\n');
        }
        var prefs = memory.getPreferences(userId);
        if (!prefs.notes().isEmpty()) {
            sb.append("Things the user told you before: ").append(String.join("; ", prefs.notes())).append('\n');
        }
        List<ChatMessage> recent = memory.recentConversation(userId);
        if (!recent.isEmpty()) {
            sb.append("Recent conversation (oldest first):\n");
            for (ChatMessage m : recent) {
                String t = m.getText().length() > 300 ? m.getText().substring(0, 300) + "..." : m.getText();
                sb.append(m.getRole() == ChatMessage.Role.USER ? "User: " : "Agent: ").append(t.replace('\n', ' ')).append('\n');
            }
        }
        return sb.toString();
    }

    /** Finds which owned plant the user means, by name, nickname or species; if they only have one, that one. */
    OwnedPlant resolveOwned(long userId, String name, String message) {
        List<OwnedPlant> plants = plantRepository.findAll(userId);
        if (plants.isEmpty()) {
            return null;
        }
        Optional<OwnedPlant> byName = matchByName(plants, name, OwnedPlant::getDisplayName, OwnedPlant::getScientificName)
                .or(() -> matchByName(plants, name,
                        p -> p.getCommonName() != null ? p.getCommonName() : p.getScientificName(), OwnedPlant::getScientificName));
        if (byName.isPresent()) {
            return byName.get();
        }
        if (message != null && !message.isBlank()) {
            String m = message.toLowerCase(Locale.ROOT);
            Optional<OwnedPlant> mentioned = plants.stream()
                    .filter(p -> m.contains(p.getDisplayName().toLowerCase(Locale.ROOT))
                            || (p.getCommonName() != null && m.contains(p.getCommonName().toLowerCase(Locale.ROOT)))
                            || m.contains(p.getScientificName().split(" ")[0].toLowerCase(Locale.ROOT)))
                    .findFirst();
            if (mentioned.isPresent()) {
                return mentioned.get();
            }
        }
        return plants.size() == 1 ? plants.get(0) : null;
    }

    /**
     * Best match for a name the user typed. Scores: exact name or species (3), the genus
     * ("monstera" for Monstera deliciosa) (2), the name merely contains the word (1);
     * ties go to the shorter name, so "monstera" prefers "Monstera deliciosa" over "Mini monstera".
     */
    private static <T> Optional<T> matchByName(List<T> items, String name, java.util.function.Function<T, String> primary,
                                               java.util.function.Function<T, String> secondary) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        String n = name.toLowerCase(Locale.ROOT).replaceAll("\\b(my|the|a)\\b", "").trim();
        if (n.isEmpty()) {
            return Optional.empty();
        }
        T best = null;
        int bestScore = 0;
        int bestLength = Integer.MAX_VALUE;
        for (T item : items) {
            String a = primary.apply(item).toLowerCase(Locale.ROOT);
            String b = secondary.apply(item).toLowerCase(Locale.ROOT);
            int score = a.equals(n) || b.equals(n) ? 3
                    : b.split(" ")[0].equals(n) ? 2
                    : a.contains(n) || n.contains(a) || b.contains(n) || n.contains(b) ? 1 : 0;
            if (score > bestScore || (score == bestScore && score > 0 && a.length() < bestLength)) {
                best = item;
                bestScore = score;
                bestLength = a.length();
            }
        }
        return Optional.ofNullable(best);
    }

    private ChatReply whichPlant(long userId, String question, String sendPrefix, String kind) {
        List<OwnedPlant> plants = plantRepository.findAll(userId);
        if (plants.isEmpty()) {
            return ChatReply.of("You haven't added any plants yet. Identify one or tell me what you have, e.g. "
                    + "\"I just bought a snake plant\".", kind);
        }
        return new ChatReply(question, plants.stream().limit(8)
                .map(p -> ChatAction.send(p.getDisplayName(), sendPrefix + p.getDisplayName())).toList(), kind, List.of());
    }

    private static ChatReply reply(String text, String intent, AgentResponse response, List<ChatAction> actions) {
        return new ChatReply(text, actions, intent, response.toolsUsed());
    }

    private static String healthNote(HealthRecord r) {
        return "Health check (" + r.getStatus().name().replace('_', ' ').toLowerCase(Locale.ROOT) + ", "
                + r.getHealthScore() + "/10): " + r.getSummary() + "\nWhat to do:\n" + bullets(r.getCareSteps());
    }

    private static String bullets(String lines) {
        if (lines == null || lines.isBlank()) {
            return "- (none noted)";
        }
        return String.join("\n", lines.lines().filter(l -> !l.isBlank()).map(l -> "- " + l.trim()).toList());
    }

    private static String oneLine(String lines) {
        return lines == null ? "unknown" : String.join("; ", lines.lines().filter(l -> !l.isBlank()).toList());
    }

    private static String notesSuffix(List<String> notes) {
        return notes.isEmpty() ? "" : "\n\n_" + String.join(" ", notes) + "_";
    }

    private static <T> List<T> concat(List<T> a, List<T> b) {
        List<T> all = new ArrayList<>(a);
        all.addAll(b);
        return all;
    }

    private static String firstNonBlank(String a, String b) {
        return a != null && !a.isBlank() ? a : b;
    }

    private static String str(Map<String, Object> p, String key) {
        Object v = p.get(key);
        return v == null ? null : v.toString();
    }

    private static Long lng(Map<String, Object> p, String key) {
        Object v = p.get(key);
        if (v instanceof Number n) {
            return n.longValue();
        }
        return v == null || v.toString().isBlank() ? null : Long.valueOf(v.toString());
    }
}
