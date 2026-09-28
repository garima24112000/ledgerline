package com.ledgerline.gateway.api;

import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.method.ParameterErrors;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/** Maps every exception that leaves a controller to the {@link ApiError} format. */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    ResponseEntity<ApiError> api(ApiException e) {
        return error(e.getStatus(), e.getCode(), e.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiError> invalidBody(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + " " + error.getDefaultMessage())
                .sorted()
                .collect(Collectors.joining("; "));
        return error(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", message);
    }

    /**
     * Constraint annotations on @RequestParam / @RequestHeader parameters. Once a handler method has
     * such constraints, Spring validates its @Valid body in the same pass, so body field errors
     * arrive here too (as {@link ParameterErrors}).
     */
    @ExceptionHandler(HandlerMethodValidationException.class)
    ResponseEntity<ApiError> invalidParameter(HandlerMethodValidationException e) {
        String message = e.getAllValidationResults().stream()
                .flatMap(result -> result instanceof ParameterErrors body
                        ? body.getFieldErrors().stream().map(error -> error.getField() + " " + error.getDefaultMessage())
                        : result.getResolvableErrors().stream()
                                .map(error -> result.getMethodParameter().getParameterName() + " " + error.getDefaultMessage()))
                .sorted()
                .collect(Collectors.joining("; "));
        return error(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", message);
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    ResponseEntity<ApiError> missingHeader(MissingRequestHeaderException e) {
        if ("Idempotency-Key".equalsIgnoreCase(e.getHeaderName())) {
            return error(HttpStatus.BAD_REQUEST, "MISSING_IDEMPOTENCY_KEY", "The Idempotency-Key header is required");
        }
        return error(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Missing header " + e.getHeaderName());
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<ApiError> typeMismatch(MethodArgumentTypeMismatchException e) {
        return error(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Invalid value for " + e.getName());
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ApiError> unreadable(HttpMessageNotReadableException e) {
        return error(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Request body is missing or is not valid JSON");
    }

    /** Two writers changed the same payment at once; the loser rolled back and can retry. */
    @ExceptionHandler(OptimisticLockingFailureException.class)
    ResponseEntity<ApiError> concurrentUpdate(OptimisticLockingFailureException e) {
        return error(HttpStatus.CONFLICT, "CONCURRENT_UPDATE", "The resource was changed concurrently; retry the request");
    }

    /** Anything else. Spring MVC's own exceptions (404 route, 405, 415, ...) keep their status. */
    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiError> unexpected(Exception e) {
        if (e instanceof ErrorResponse springError) {
            HttpStatusCode status = springError.getStatusCode();
            HttpStatus known = HttpStatus.resolve(status.value());
            String code = known == null ? "ERROR" : known.name();
            return ResponseEntity.status(status).body(ApiError.of(code, springError.getBody().getDetail()));
        }
        log.error("Unhandled exception", e);
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "Something went wrong");
    }

    private static ResponseEntity<ApiError> error(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).body(ApiError.of(code, message));
    }
}
