package com.plantorganizer.common;

/** Thrown when the current user may not see or change a record (e.g. a friend's private plants). Mapped to HTTP 403. */
public class ForbiddenException extends RuntimeException {

    public ForbiddenException(String message) {
        super(message);
    }
}
