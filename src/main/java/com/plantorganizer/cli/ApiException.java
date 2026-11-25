package com.plantorganizer.cli;

/**
 * The server answered with an error, e.g. {@code {"error": "Plant not found."}}
 * from its ApiExceptionHandler. The message is the server's own wording.
 */
public class ApiException extends CliException {

    private final int status;
    private final boolean retryable;

    public ApiException(int status, String message, boolean retryable) {
        super(message);
        this.status = status;
        this.retryable = retryable;
    }

    /** HTTP status code, e.g. 404; 0 when the request timed out. */
    public int status() {
        return status;
    }

    /** True when trying again later may work (an external service was busy or timed out). */
    public boolean retryable() {
        return retryable;
    }
}
