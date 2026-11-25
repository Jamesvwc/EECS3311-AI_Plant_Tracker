package com.plantorganizer.agent;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import org.springframework.stereotype.Component;

/**
 * Changes the chat agent has proposed but not made yet (Command pattern).
 *
 * When a chat message asks for a change, ChatAgent first works out exactly
 * what it would do (which plant, which space, the new values) without
 * touching anything, and stores that work here as a {@link PendingChange}: a
 * description the user reads, and the code that makes the change. The chat
 * box shows the description with a Yes button. Only pressing that button runs
 * the change, and it runs exactly what was described, once.
 *
 * Proposals are kept in memory: each expires after {@link #LIFETIME}, a user
 * has at most {@link #MAX_PER_USER} waiting, and clearing the conversation
 * drops them.
 */
@Component
public class PendingChanges {

    static final Duration LIFETIME = Duration.ofMinutes(30);
    static final int MAX_PER_USER = 10;

    /** Payload key of the APPROVE and DECLINE buttons that refer to a proposal. */
    public static final String PROPOSAL_ID = "proposalId";

    /**
     * One proposed change.
     *
     * @param intent       the planner intent it came from, e.g. "MARK_WATERED"
     * @param description  what will change, as shown to the user
     * @param approveLabel the label of its Yes button
     * @param change       makes the change and describes the result; runs only on approval
     */
    public record PendingChange(String id, long userId, String intent, String description, String approveLabel,
                                Supplier<ChatReply> change, LocalDateTime proposedAt) {
    }

    private final Clock clock;
    private final Map<Long, LinkedHashMap<String, PendingChange>> waiting = new HashMap<>();
    /** The approval buttons of the agent's latest reply to each user, for a typed "yes" or "no". */
    private final Map<Long, List<ChatAction>> offers = new HashMap<>();

    public PendingChanges(Clock clock) {
        this.clock = clock;
    }

    public synchronized PendingChange propose(long userId, String intent, String description, String approveLabel,
                                              Supplier<ChatReply> change) {
        LinkedHashMap<String, PendingChange> mine = live(userId);
        PendingChange proposal = new PendingChange(UUID.randomUUID().toString(), userId, intent, description,
                approveLabel, change, LocalDateTime.now(clock));
        mine.put(proposal.id(), proposal);
        Iterator<String> oldestFirst = mine.keySet().iterator();
        while (mine.size() > MAX_PER_USER) {
            oldestFirst.next();
            oldestFirst.remove();
        }
        return proposal;
    }

    /**
     * Removes the proposal and returns it so it can run once. Empty when the
     * id is unknown or belongs to another user, or the proposal was already
     * approved, declined or has expired.
     */
    public synchronized Optional<PendingChange> take(long userId, String id) {
        return id == null ? Optional.empty() : Optional.ofNullable(live(userId).remove(id));
    }

    public synchronized boolean isWaiting(long userId, String id) {
        return id != null && live(userId).containsKey(id);
    }

    /** The proposal if it is still waiting, without taking it. */
    public synchronized Optional<PendingChange> peek(long userId, String id) {
        return id == null ? Optional.empty() : Optional.ofNullable(live(userId).get(id));
    }

    /** Remembers which approval buttons (if any) the agent's latest reply offered. */
    public synchronized void offered(long userId, ChatReply reply) {
        offers.put(userId, reply.actions().stream().filter(PendingChanges::refersToProposal).toList());
    }

    /** The approval buttons of the agent's latest reply whose proposals are still waiting. */
    public synchronized List<ChatAction> lastOffer(long userId) {
        return offers.getOrDefault(userId, List.of()).stream()
                .filter(a -> isWaiting(userId, proposalId(a))).toList();
    }

    public synchronized void clear(long userId) {
        waiting.remove(userId);
        offers.remove(userId);
    }

    /** An APPROVE or DECLINE button for this proposal. */
    static ChatAction button(String label, String type, PendingChange proposal) {
        return new ChatAction(label, type, Map.of(PROPOSAL_ID, proposal.id()));
    }

    static String proposalId(ChatAction action) {
        Object id = action.payload().get(PROPOSAL_ID);
        return id == null ? null : id.toString();
    }

    private static boolean refersToProposal(ChatAction action) {
        return proposalId(action) != null;
    }

    private LinkedHashMap<String, PendingChange> live(long userId) {
        LinkedHashMap<String, PendingChange> mine = waiting.computeIfAbsent(userId, k -> new LinkedHashMap<>());
        LocalDateTime cutoff = LocalDateTime.now(clock).minus(LIFETIME);
        mine.values().removeIf(p -> p.proposedAt().isBefore(cutoff));
        return mine;
    }
}
