package com.plantorganizer.common;

/**
 * Thrown by an adapter when an external service (Pl@ntNet, Perenual, Cohere)
 * cannot give a usable answer: the key is missing, the network failed, the
 * service returned an error, or the plan does not cover the request.
 *
 * {@code retryable} tells callers whether sending the same request again could
 * help. A timeout or a 5xx error is retryable; a missing API key or a
 * "premium plan only" response is not.
 */
public class ExternalServiceException extends RuntimeException {

    private final String service;
    private final boolean retryable;

    public ExternalServiceException(String service, String message, boolean retryable) {
        this(service, message, retryable, null);
    }

    public ExternalServiceException(String service, String message, boolean retryable, Throwable cause) {
        super(service + ": " + message, cause);
        this.service = service;
        this.retryable = retryable;
    }

    public String getService() {
        return service;
    }

    public boolean isRetryable() {
        return retryable;
    }
}
