package com.plantorganizer.memory;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Stage 1 AgentMemory: the agent's long-term memory of the user's tastes
 * (stored in its own table, as Stage 1 required) and short-term memory of the
 * chat conversation. Likes come from plants the user owns or accepts,
 * dislikes from "unrecommend", and notes from preferences stated in the chat.
 */
@Component
@Transactional
public class AgentMemory {

    private final UserPreferenceJpaRepository preferences;
    private final ChatMessageJpaRepository messages;
    private final Clock clock;

    public AgentMemory(UserPreferenceJpaRepository preferences, ChatMessageJpaRepository messages, Clock clock) {
        this.preferences = preferences;
        this.messages = messages;
        this.clock = clock;
    }

    /**
     * Remembers a preference. Saying you like something you previously
     * disliked (or the reverse) replaces the old opinion.
     */
    public void savePreference(long userId, UserPreference.Kind kind, String subject) {
        if (subject == null || subject.isBlank()) {
            return;
        }
        String s = subject.trim();
        for (UserPreference existing : preferences.findByOwnerIdOrderByCreatedAtDesc(userId)) {
            if (existing.getSubject().equalsIgnoreCase(s)) {
                if (existing.getKind() == kind) {
                    return;
                }
                preferences.delete(existing);
            }
        }
        preferences.save(new UserPreference(userId, kind, s, LocalDateTime.now(clock)));
    }

    @Transactional(readOnly = true)
    public UserPreferenceModel getPreferences(long userId) {
        List<String> likes = new ArrayList<>();
        List<String> dislikes = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        for (UserPreference p : preferences.findByOwnerIdOrderByCreatedAtDesc(userId)) {
            switch (p.getKind()) {
                case LIKE -> likes.add(p.getSubject());
                case DISLIKE -> dislikes.add(p.getSubject());
                case NOTE -> notes.add(p.getSubject());
            }
        }
        return new UserPreferenceModel(likes, dislikes, notes);
    }

    @Transactional(readOnly = true)
    public List<UserPreference> listPreferences(long userId) {
        return preferences.findByOwnerIdOrderByCreatedAtDesc(userId);
    }

    public void forget(long userId, long preferenceId) {
        preferences.findById(preferenceId).filter(p -> p.getOwnerId() == userId).ifPresent(preferences::delete);
    }

    public void remember(long userId, ChatMessage.Role role, String text) {
        if (text != null && !text.isBlank()) {
            messages.save(new ChatMessage(userId, role, text, LocalDateTime.now(clock)));
        }
    }

    /** The last few chat messages, oldest first, for the LLM's context. */
    @Transactional(readOnly = true)
    public List<ChatMessage> recentConversation(long userId) {
        List<ChatMessage> recent = new ArrayList<>(messages.findTop12ByOwnerIdOrderByCreatedAtDescIdDesc(userId));
        java.util.Collections.reverse(recent);
        return recent;
    }

    @Transactional(readOnly = true)
    public List<ChatMessage> history(long userId) {
        List<ChatMessage> all = new ArrayList<>(messages.findTop100ByOwnerIdOrderByCreatedAtDescIdDesc(userId));
        java.util.Collections.reverse(all);
        return all;
    }

    public void clearConversation(long userId) {
        messages.deleteByOwnerId(userId);
    }
}
