package com.example.inventory.web;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import com.example.inventory.inventory.InsufficientStockException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private static final String PROBLEM_BASE = "https://api.example.com/problems/";

    static final String INSUFFICIENT_PRIVILEGE = SqlStates.INSUFFICIENT_PRIVILEGE;

    @ExceptionHandler(UnauthorizedException.class)
    ResponseEntity<ProblemDetail> unauthorized(UnauthorizedException e) {
        return problem(HttpStatus.UNAUTHORIZED, "Authentication failed", e.getMessage(),
                "invalid-credentials");
    }

    @ExceptionHandler(ConflictException.class)
    ResponseEntity<ProblemDetail> conflict(ConflictException e) {
        return problem(HttpStatus.CONFLICT, "Conflict", e.getMessage(), e.problemType());
    }

    @ExceptionHandler(RateLimitedException.class)
    ResponseEntity<ProblemDetail> rateLimited(RateLimitedException e) {
        ResponseEntity<ProblemDetail> response = problem(
                HttpStatus.TOO_MANY_REQUESTS, "Too many requests", e.getMessage(), "rate-limited");
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, String.valueOf(e.retryAfter().toSeconds()))
                .body(response.getBody());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ProblemDetail> validation(MethodArgumentNotValidException e) {
        List<Map<String, String>> errors = e.getBindingResult().getFieldErrors().stream()
                .map(f -> Map.of(
                        "field", f.getField(),
                        "message", f.getDefaultMessage() == null ? "is invalid" : f.getDefaultMessage()))
                .toList();

        ResponseEntity<ProblemDetail> response = problem(
                HttpStatus.UNPROCESSABLE_ENTITY, "Validation failed",
                "One or more fields are invalid", "validation-failed");
        response.getBody().setProperty("errors", errors);
        return response;
    }

    @ExceptionHandler(InsufficientStockException.class)
    ResponseEntity<ProblemDetail> insufficientStock(InsufficientStockException e) {
        ResponseEntity<ProblemDetail> response = problem(HttpStatus.CONFLICT,
                "Insufficient stock",
                "The requested quantity is not available", "insufficient-stock");

        ProblemDetail body = response.getBody();
        body.setProperty("productId", e.productId());
        body.setProperty("requested", e.requested());
        body.setProperty("available", e.available());
        return response;
    }

    @ExceptionHandler(PreconditionFailedException.class)
    ResponseEntity<ProblemDetail> preconditionFailed(PreconditionFailedException e) {
        return problem(HttpStatus.PRECONDITION_FAILED, "Precondition failed", e.getMessage(),
                "stale-write");
    }

    @ExceptionHandler(BadRequestException.class)
    ResponseEntity<ProblemDetail> badRequest(BadRequestException e) {
        return problem(HttpStatus.BAD_REQUEST, "Bad request", e.getMessage(), "bad-request");
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<ProblemDetail> typeMismatch(MethodArgumentTypeMismatchException e) {
        String type = e.getRequiredType() == null
                ? "the expected type" : e.getRequiredType().getSimpleName();
        return problem(HttpStatus.BAD_REQUEST, "Bad request",
                "Parameter '" + e.getName() + "' could not be read as " + type + ".",
                "bad-request");
    }

    @ExceptionHandler(NotFoundException.class)
    ResponseEntity<ProblemDetail> notFound(NotFoundException e) {
        return problem(HttpStatus.NOT_FOUND, "Not found", e.getMessage(), "not-found");
    }

    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<ProblemDetail> accessDenied(AccessDeniedException e) {
        return problem(HttpStatus.FORBIDDEN, "Forbidden",
                "Your role does not permit this action", "insufficient-role");
    }

    @ExceptionHandler(DataAccessException.class)
    ResponseEntity<ProblemDetail> dataAccess(DataAccessException e) {
        String sqlState = sqlStateOf(e);

        if (INSUFFICIENT_PRIVILEGE.equals(sqlState)) {
            log.error("SQLSTATE 42501 — a statement was refused by a privilege or an RLS policy. "
                    + "If this was a WITH CHECK failure it is an attempted cross-tenant write, "
                    + "not a malformed query. Cause: {}", rootMessage(e));

            return problem(HttpStatus.INTERNAL_SERVER_ERROR, "Internal server error",
                    "The request could not be processed", "internal-error");
        }

        log.error("Unhandled data access failure (SQLSTATE {})", sqlState, e);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "Internal server error",
                "The request could not be processed", "internal-error");
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemDetail> unexpected(Exception e) {
        if (e instanceof ErrorResponse errorResponse) {
            HttpStatus status = HttpStatus.valueOf(errorResponse.getStatusCode().value());
            return problem(status, status.getReasonPhrase(),
                    "The request could not be handled as sent", "bad-request");
        }

        log.error("Unhandled exception", e);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "Internal server error",
                "The request could not be processed", "internal-error");
    }

    static String sqlStateOf(Throwable e) {
        return SqlStates.of(e);
    }

    private static String rootMessage(Throwable e) {
        return SqlStates.rootMessage(e);
    }

    private static ResponseEntity<ProblemDetail> problem(
            HttpStatus status, String title, String detail, String problemType) {
        ProblemDetail body = ProblemDetail.forStatusAndDetail(status, detail);
        body.setTitle(title);
        body.setType(URI.create(PROBLEM_BASE + problemType));
        currentTraceId().ifPresent(id -> body.setProperty("traceId", id));
        return ResponseEntity.status(status).body(body);
    }

    private static Optional<String> currentTraceId() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (attributes == null) {
            return Optional.empty();
        }
        Object id = attributes.getAttribute("traceId", RequestAttributes.SCOPE_REQUEST);
        return Optional.ofNullable(id).map(Object::toString);
    }
}
