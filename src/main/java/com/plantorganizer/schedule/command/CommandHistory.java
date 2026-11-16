package com.plantorganizer.schedule.command;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Component;

/**
 * Command pattern, invoker role: runs each calendar command and remembers it
 * (per user, most recent 20) so {@link #undoLast} can reverse the latest one.
 * The history lives in memory, so it is cleared when the server restarts.
 */
@Component
public class CommandHistory {

    static final int MAX_PER_USER = 20;

    private final Map<Long, Deque<ScheduleCommand>> history = new HashMap<>();

    public synchronized void run(long userId, ScheduleCommand command) {
        command.execute();
        Deque<ScheduleCommand> commands = history.computeIfAbsent(userId, id -> new ArrayDeque<>());
        commands.push(command);
        while (commands.size() > MAX_PER_USER) {
            commands.removeLast();
        }
    }

    /** Undoes the user's most recent command and returns its description, or empty if there is nothing to undo. */
    public synchronized Optional<String> undoLast(long userId) {
        Deque<ScheduleCommand> commands = history.get(userId);
        if (commands == null || commands.isEmpty()) {
            return Optional.empty();
        }
        ScheduleCommand command = commands.pop();
        command.undo();
        return Optional.of(command.description());
    }

    public synchronized Optional<String> peekLast(long userId) {
        Deque<ScheduleCommand> commands = history.get(userId);
        return commands == null || commands.isEmpty() ? Optional.empty() : Optional.of(commands.peek().description());
    }
}
