package com.ulab.agent.api;

import com.ulab.agent.utils.Lang;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.Map;
import java.util.stream.Collectors;

/**
 * Turns every exception that escapes a controller into the same small shape:
 * {"error": "...", "detail": "..."}.
 *
 * "error" is the sentence a person should read; "detail" is the technical part,
 * which the panel shows only when it has one. Nothing here ever puts a stack
 * trace or a credential in the response body.
 */
@RestControllerAdvice
public class ApiExceptionAdvice {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionAdvice.class);

    /** Thrown deliberately by services to pick their own status, e.g. 404. */
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, String>> handleStatus(ResponseStatusException e) {
        String message = e.getReason() == null ? Lang.ERR_UNEXPECTED : e.getReason();
        return ResponseEntity.status(e.getStatusCode()).body(body(message, null));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, String>> handleValidation(MethodArgumentNotValidException e) {
        String detail = e.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .collect(Collectors.joining("; "));
        return ResponseEntity.badRequest().body(body(Lang.ERR_VALIDATION, detail));
    }

    /** A unique index or foreign key said no — the caller's change clashes with stored data. */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<Map<String, String>> handleConflict(DataIntegrityViolationException e) {
        log.warn("Rejected by a database constraint: {}", e.getMostSpecificCause().getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(body(Lang.ERR_CONFLICT, null));
    }

    /**
     * A path that names no file and no endpoint.
     *
     * Without this it fell through to the catch-all below and became a 500 with
     * a full stack trace in the log. Every browser asks for /favicon.ico on
     * every page load and this app has none, so a normal afternoon of using the
     * panel wrote hundreds of stack traces to a file that is now rotated and
     * kept — and any mistyped API path answered "the server is broken" when the
     * truth was "there is nothing here".
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Map<String, String>> handleMissing(NoResourceFoundException e) {
        log.debug("Nothing at {}", e.getResourcePath());
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(body(Lang.ERR_NOT_FOUND, null));
    }

    /**
     * A value in the address that is not the kind of value it should be — a
     * date that is not a date, an id that is not an id.
     *
     * Same fault as the one above and found the same way: it fell through to
     * the catch-all, so mistyping a date in the report's address answered "the
     * server is broken" and wrote a stack trace, when the truth was that the
     * caller had asked for something unreadable. The parameter is named in the
     * detail; the value it was given is not, because it came from outside and
     * an error page is not a place to hand it back.
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Map<String, String>> handleBadParameter(
            MethodArgumentTypeMismatchException e) {
        Class<?> wanted = e.getRequiredType();
        String detail = e.getName() + ": " + (wanted == null ? "?" : wanted.getSimpleName());
        log.debug("Unreadable value for {}", e.getName());
        return ResponseEntity.badRequest().body(body(Lang.ERR_BAD_PARAMETER, detail));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> handleEverythingElse(Exception e) {
        log.error("Unhandled error while serving a request", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(body(Lang.ERR_UNEXPECTED, null));
    }

    private static Map<String, String> body(String error, String detail) {
        return detail == null ? Map.of("error", error) : Map.of("error", error, "detail", detail);
    }
}
