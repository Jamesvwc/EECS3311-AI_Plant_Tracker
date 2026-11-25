package com.plantorganizer.cli;

import picocli.CommandLine;

/**
 * A command was used the wrong way in a way the command itself checks (a noun
 * without a verb, an empty message...). Unlike picocli's own parse errors it
 * is not sent to the agent for a "did you mean" suggestion: the message
 * already says exactly what to type.
 */
public class UsageException extends CommandLine.ParameterException {

    public UsageException(CommandLine commandLine, String message) {
        super(commandLine, message);
    }
}
