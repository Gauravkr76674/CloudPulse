package com.cloudops.agent.exception;

import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import com.cloudops.agent.dto.ApiError;

import jakarta.validation.ConstraintViolationException;

/**
 * Translates exceptions into the {@link ApiError} shape the React app expects.
 * Stack traces are logged server-side; clients only ever see a message.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<ApiError> handleNotFound(NotFoundException ex, WebRequest request) {
        return build(HttpStatus.NOT_FOUND, ex.getMessage(), request);
    }

    @ExceptionHandler(BadRequestException.class)
    public ResponseEntity<ApiError> handleBadRequest(BadRequestException ex, WebRequest request) {
        return build(HttpStatus.BAD_REQUEST, ex.getMessage(), request);
    }

    @ExceptionHandler(ConflictException.class)
    public ResponseEntity<ApiError> handleConflict(ConflictException ex, WebRequest request) {
        return build(HttpStatus.CONFLICT, ex.getMessage(), request);
    }

    @ExceptionHandler(ActionNotAllowedException.class)
    public ResponseEntity<ApiError> handleActionNotAllowed(ActionNotAllowedException ex, WebRequest request) {
        log.warn("Remediation guardrail refused an action: {}", ex.getMessage());
        return build(HttpStatus.UNPROCESSABLE_ENTITY, ex.getMessage(), request);
    }

    @ExceptionHandler(AiServiceException.class)
    public ResponseEntity<ApiError> handleAi(AiServiceException ex, WebRequest request) {
        log.error("AI layer failure: {}", ex.getMessage());
        return build(HttpStatus.BAD_GATEWAY, ex.getMessage(), request);
    }

    @ExceptionHandler(CloudProviderException.class)
    public ResponseEntity<ApiError> handleCloud(CloudProviderException ex, WebRequest request) {
        log.error("Cloud provider failure: {}", ex.getMessage());
        return build(HttpStatus.BAD_GATEWAY, ex.getMessage(), request);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiError> handleAccessDenied(AccessDeniedException ex, WebRequest request) {
        return build(HttpStatus.FORBIDDEN, "You do not have permission to perform this action", request);
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ApiError> handleAuthentication(AuthenticationException ex, WebRequest request) {
        return build(HttpStatus.UNAUTHORIZED, "Invalid username or password", request);
    }

    /** @Valid failures on request bodies. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException ex, WebRequest request) {
        Map<String, String> fieldErrors = new LinkedHashMap<>();
        for (FieldError fieldError : ex.getBindingResult().getFieldErrors()) {
            fieldErrors.putIfAbsent(fieldError.getField(), fieldError.getDefaultMessage());
        }
        ApiError body = ApiError.withFields(HttpStatus.BAD_REQUEST.value(),
                HttpStatus.BAD_REQUEST.getReasonPhrase(),
                "Validation failed", path(request), fieldErrors);
        return ResponseEntity.badRequest().body(body);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiError> handleConstraintViolation(ConstraintViolationException ex, WebRequest request) {
        return build(HttpStatus.BAD_REQUEST, ex.getMessage(), request);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiError> handleIllegalArgument(IllegalArgumentException ex, WebRequest request) {
        return build(HttpStatus.BAD_REQUEST, ex.getMessage(), request);
    }

    /**
     * A path variable or query parameter that cannot be converted to the declared type, e.g.
     * {@code /api/incidents/abc} for a {@code Long id}, or an unknown enum name for a filter.
     * This is the caller's mistake, so it must be 400 and not fall through to the 500 handler.
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiError> handleTypeMismatch(MethodArgumentTypeMismatchException ex,
                                                       WebRequest request) {
        Class<?> required = ex.getRequiredType();
        String expected = required == null ? "the expected type" : describe(required);
        String message = "'" + ex.getName() + "' must be " + expected
                + " but was \"" + ex.getValue() + "\"";
        log.debug("Rejected a malformed request parameter: {}", message);
        return build(HttpStatus.BAD_REQUEST, message, request);
    }

    /** A missing required query parameter is also a client error. */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiError> handleMissingParameter(MissingServletRequestParameterException ex,
                                                          WebRequest request) {
        return build(HttpStatus.BAD_REQUEST,
                "Required parameter '" + ex.getParameterName() + "' is missing", request);
    }

    /** Malformed or absent JSON body. */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> handleUnreadableBody(HttpMessageNotReadableException ex,
                                                         WebRequest request) {
        // The exception message can echo the raw payload, so it is not returned to the client.
        log.debug("Rejected an unreadable request body: {}", ex.getMessage());
        return build(HttpStatus.BAD_REQUEST, "Request body is missing or not valid JSON", request);
    }

    /**
     * Spring Data throws this for things like {@code findById(null)}. It means a required
     * identifier never arrived, which is a bad request rather than a server fault. Services
     * validate their own arguments, so reaching here is a defence in depth.
     */
    @ExceptionHandler(InvalidDataAccessApiUsageException.class)
    public ResponseEntity<ApiError> handleInvalidDataAccess(InvalidDataAccessApiUsageException ex,
                                                           WebRequest request) {
        log.warn("Rejected an invalid data access call: {}", ex.getMessage());
        return build(HttpStatus.BAD_REQUEST, "A required identifier was missing or invalid", request);
    }

    /** Last resort: never leak an internal stack trace to the client. */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnexpected(Exception ex, WebRequest request) {
        log.error("Unhandled exception", ex);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "Unexpected server error", request);
    }

    /** "a number", "one of OPEN, ACKNOWLEDGED, ..." — enough for the caller to fix the call. */
    private String describe(Class<?> required) {
        if (Number.class.isAssignableFrom(required)
                || required == int.class || required == long.class || required == double.class) {
            return "a number";
        }
        if (required == Boolean.class || required == boolean.class) {
            return "true or false";
        }
        if (required.isEnum()) {
            StringBuilder names = new StringBuilder("one of ");
            Object[] constants = required.getEnumConstants();
            for (int i = 0; i < constants.length; i++) {
                if (i > 0) {
                    names.append(", ");
                }
                names.append(constants[i]);
            }
            return names.toString();
        }
        return "a valid " + required.getSimpleName();
    }

    private ResponseEntity<ApiError> build(HttpStatus status, String message, WebRequest request) {
        ApiError body = ApiError.of(status.value(), status.getReasonPhrase(), message, path(request));
        return ResponseEntity.status(status).body(body);
    }

    private String path(WebRequest request) {
        String description = request.getDescription(false);
        return description == null ? "" : description.replace("uri=", "");
    }
}
