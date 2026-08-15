package com.dataentry.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** Safe generic message for 500s so we never leak stack traces / DB errors to clients. */
    private static final String GENERIC_500_MESSAGE = "Unexpected server error. Please try again later.";

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException ex) {
        Map<String, String> fieldErrors = new HashMap<>();
        ex.getBindingResult().getFieldErrors().forEach(fe ->
                fieldErrors.put(fe.getField(), fe.getDefaultMessage()));
        return build(HttpStatus.BAD_REQUEST, "Validation failed", fieldErrors);
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, Object>> handleStatus(ResponseStatusException ex) {
        return build(HttpStatus.valueOf(ex.getStatusCode().value()), ex.getReason(), null);
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Map<String, Object>> handleMissing(NoResourceFoundException ex) {
        return build(HttpStatus.NOT_FOUND, "Resource not found", null);
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Map<String, Object>> handleTooLarge(MaxUploadSizeExceededException ex) {
        return build(HttpStatus.PAYLOAD_TOO_LARGE,
                "Uploaded file is too large. Maximum allowed size is 200 MB.", null);
    }

    /**
     * Return the semantically-correct 415 when a client sends the wrong Content-Type (e.g.
     * JSON to a multipart-only endpoint). Without this handler the exception would fall
     * through to {@link #handleOther} and surface as an opaque 500 "Unexpected server error"
     * — which is exactly what tripped up the axios FormData upload bug.
     */
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<Map<String, Object>> handleUnsupportedMediaType(
            HttpMediaTypeNotSupportedException ex) {
        String detail = ex.getContentType() == null
                ? "Content-Type is missing or not supported for this endpoint."
                : "Content-Type '" + ex.getContentType() + "' is not supported for this endpoint.";
        return build(HttpStatus.UNSUPPORTED_MEDIA_TYPE, detail, null);
    }

    /**
     * Any unhandled exception. Log the full stack trace server-side, but return a generic
     * message to the client — never expose ex.getMessage() (may contain stack fragments,
     * DB constraint text, file paths, etc.).
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleOther(Exception ex) {
        log.error("Unhandled exception: {}", ex.getMessage(), ex);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, GENERIC_500_MESSAGE, null);
    }

    private ResponseEntity<Map<String, Object>> build(HttpStatus status, String message, Object details) {
        Map<String, Object> body = new HashMap<>();
        body.put("timestamp", Instant.now().toString());
        body.put("status", status.value());
        body.put("error", status.getReasonPhrase());
        body.put("message", message == null ? "" : message);
        if (details != null) body.put("details", details);
        return ResponseEntity.status(status).body(body);
    }
}
