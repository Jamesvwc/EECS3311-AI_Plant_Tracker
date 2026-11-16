package com.plantorganizer.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

import com.plantorganizer.common.ExternalServiceException;
import com.plantorganizer.common.ForbiddenException;
import com.plantorganizer.common.InvalidInputException;
import com.plantorganizer.common.NotFoundException;
import com.plantorganizer.schedule.availability.ScheduleParseException;

/**
 * Turns exceptions from any REST endpoint into a small JSON error body
 * ({"error": "...", "service": "..."}) that the frontend shows to the user,
 * instead of Spring's default HTML/stack-trace error page.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    public record ApiError(String error, String service, boolean retryable) {
    }

    @ExceptionHandler(InvalidInputException.class)
    public ResponseEntity<ApiError> invalidInput(InvalidInputException e) {
        return ResponseEntity.badRequest().body(new ApiError(e.getMessage(), null, false));
    }

    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<ApiError> notFound(NotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ApiError(e.getMessage(), null, false));
    }

    @ExceptionHandler(ForbiddenException.class)
    public ResponseEntity<ApiError> forbidden(ForbiddenException e) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(new ApiError(e.getMessage(), null, false));
    }

    /** F03: the availability could not be understood; the message asks the user to clarify. */
    @ExceptionHandler(ScheduleParseException.class)
    public ResponseEntity<ApiError> unparseable(ScheduleParseException e) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_CONTENT).body(new ApiError(e.getMessage(), null, false));
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, MissingServletRequestPartException.class,
            org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class,
            org.springframework.http.converter.HttpMessageNotReadableException.class,
            org.springframework.web.bind.MissingServletRequestParameterException.class})
    public ResponseEntity<ApiError> badRequest(Exception e) {
        return ResponseEntity.badRequest().body(new ApiError("The request was missing required data.", null, false));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiError> tooLarge(MaxUploadSizeExceededException e) {
        return ResponseEntity.status(HttpStatus.CONTENT_TOO_LARGE)
                .body(new ApiError("The photos are too large. Each photo must be under 20 MB.", null, false));
    }

    @ExceptionHandler(ExternalServiceException.class)
    public ResponseEntity<ApiError> externalService(ExternalServiceException e) {
        log.warn("External service failure: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(new ApiError(e.getMessage(), e.getService(), e.isRetryable()));
    }
}
