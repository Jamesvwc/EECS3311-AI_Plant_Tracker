package com.plantorganizer.common;

/**
 * Thrown when the user's input cannot be processed, for example an upload
 * that is not a JPEG/PNG image or too many photos at once. The web layer
 * turns it into an HTTP 400 response with this message.
 */
public class InvalidInputException extends RuntimeException {

    public InvalidInputException(String message) {
        super(message);
    }
}
