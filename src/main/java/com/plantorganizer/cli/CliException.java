package com.plantorganizer.cli;

/**
 * A problem the user can fix, reported as a plain message without a stack
 * trace: a plant that isn't owned, an ambiguous name, a missing file, a
 * cancelled confirmation...
 */
public class CliException extends RuntimeException {

    public CliException(String message) {
        super(message);
    }
}
