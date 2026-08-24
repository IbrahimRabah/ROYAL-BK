package com.velora.api.common.exception;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import java.net.URI;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.NoHandlerFoundException;

/**
 * Turns every exception into one consistent RFC 7807 response, so the Angular app
 * implements a single HTTP error interceptor instead of handling each endpoint's
 * quirks.
 *
 * <pre>
 * {
 *   "type": "https://velora.com/errors/attribute-code-exists",
 *   "title": "Conflict",
 *   "status": 409,
 *   "code": "ATTRIBUTE_CODE_EXISTS",
 *   "detail": "An attribute with this code already exists...",
 *   "instance": "/api/v1/admin/attributes",
 *   "timestamp": "2026-08-09T00:04:11Z"
 * }
 * </pre>
 *
 * <p>The {@code code} is the contract. Messages are English here and translated in
 * the front end, so rewording never needs a redeploy.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private static final String ERROR_BASE = "https://velora.com/errors/";

    // ------------------------------------------------------------ business rules

    @ExceptionHandler(BusinessException.class)
    public ProblemDetail handleBusiness(BusinessException ex, HttpServletRequest request) {
        ErrorCode code = ex.getErrorCode();
        // Business rules are expected. Log without a stack trace.
        log.warn("Business rule [{}] on {}: {}", code, request.getRequestURI(), ex.getMessage());
        return build(code, ex.getMessage(), request);
    }

    // -------------------------------------------------------------- validation

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleValidation(MethodArgumentNotValidException ex,
                                          HttpServletRequest request) {
        List<FieldErrorDetail> errors = new ArrayList<>();
        for (FieldError fieldError : ex.getBindingResult().getFieldErrors()) {
            errors.add(new FieldErrorDetail(fieldError.getField(), fieldError.getDefaultMessage()));
        }
        ProblemDetail problem = build(ErrorCode.VALIDATION_FAILED,
                "One or more fields are invalid", request);
        problem.setProperty("errors", errors);
        return problem;
    }

    /** Validation on @RequestParam / @PathVariable rather than a request body. */
    @ExceptionHandler(ConstraintViolationException.class)
    public ProblemDetail handleConstraintViolation(ConstraintViolationException ex,
                                                   HttpServletRequest request) {
        List<FieldErrorDetail> errors = ex.getConstraintViolations().stream()
                .map(v -> new FieldErrorDetail(
                        v.getPropertyPath().toString(), v.getMessage()))
                .toList();
        ProblemDetail problem = build(ErrorCode.VALIDATION_FAILED,
                "One or more parameters are invalid", request);
        problem.setProperty("errors", errors);
        return problem;
    }

    /** Malformed JSON, or a field with a type the parser cannot accept. */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ProblemDetail handleUnreadableBody(HttpMessageNotReadableException ex,
                                              HttpServletRequest request) {
        log.warn("Unreadable request body on {}: {}", request.getRequestURI(), ex.getMessage());
        return build(ErrorCode.INVALID_REQUEST_BODY,
                "The request body is not valid JSON, or a field has the wrong type", request);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ProblemDetail handleTypeMismatch(MethodArgumentTypeMismatchException ex,
                                            HttpServletRequest request) {
        String detail = "'%s' is not a valid value for %s".formatted(ex.getValue(), ex.getName());
        return build(ErrorCode.INVALID_PARAMETER, detail, request);
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ProblemDetail handleMissingParam(MissingServletRequestParameterException ex,
                                            HttpServletRequest request) {
        return build(ErrorCode.INVALID_PARAMETER,
                "Required parameter '%s' is missing".formatted(ex.getParameterName()), request);
    }

    /**
     * The safety net for {@code Enum.valueOf()} (or any other input parsing) called
     * directly in application code instead of through a typed {@code @RequestParam} —
     * that path throws a bare {@code IllegalArgumentException} that skips Spring MVC's
     * own binding machinery entirely, so {@link #handleTypeMismatch} never sees it and
     * it used to fall all the way through to {@link #handleUnexpected} as a 500.
     * {@link com.velora.api.common.util.EnumParam} is the preferred fix at the call
     * site — it names the field and lists valid values — this handler exists for
     * whatever the next call site misses.
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail handleIllegalArgument(IllegalArgumentException ex,
                                               HttpServletRequest request) {
        log.warn("Invalid argument on {}: {}", request.getRequestURI(), ex.getMessage());
        return build(ErrorCode.INVALID_PARAMETER, ex.getMessage(), request);
    }

    // ---------------------------------------------------------------- database

    /**
     * SQL Server's message for a NOT NULL violation is always
     * {@code Cannot insert the value NULL into column '<col>', table '<schema.table>';
     * column does not allow nulls.} — regardless of whether the statement was an
     * INSERT or an UPDATE. The column name is the one useful, safe-to-return part of
     * that sentence; the table name stays server-side.
     */
    private static final Pattern NOT_NULL_COLUMN =
            Pattern.compile("insert the value null into column '([^']+)'", Pattern.CASE_INSENSITIVE);

    /**
     * A database constraint fired. This is the safety net: every unique index, NOT
     * NULL column and foreign key we did not check explicitly ends up here as a
     * clean 4xx instead of a 500 that tells the user nothing.
     *
     * <p>The raw SQL message names tables, so it is logged but never returned in
     * full. Three distinct failures land here and must not collapse into the same
     * code, or the client is told "this value is already in use" for a bug that has
     * nothing to do with duplication:
     * <ul>
     *   <li>a NOT NULL column the request left empty — a schema requirement the DTO
     *       does not enforce, most often because a field was deliberately dropped
     *       from the request but the column was never migrated to match</li>
     *   <li>a foreign key pointing at a parent row that does not exist (INSERT/UPDATE)</li>
     *   <li>a foreign key blocking a DELETE because child rows still reference it</li>
     *   <li>an actual unique constraint violation — the only case that is really a
     *       duplicate value</li>
     * </ul>
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ProblemDetail handleDataIntegrity(DataIntegrityViolationException ex,
                                             HttpServletRequest request) {
        String raw = rootMessage(ex);
        log.warn("Data integrity violation on {}: {}", request.getRequestURI(), raw);
        String lower = raw.toLowerCase(Locale.ENGLISH);

        Matcher notNull = NOT_NULL_COLUMN.matcher(raw);
        if (notNull.find()) {
            return build(ErrorCode.REQUIRED_FIELD_MISSING,
                    "The '%s' field is required".formatted(notNull.group(1)), request);
        }

        // SQL Server phrases these two differently: a DELETE blocked by children says
        // "conflicted with the REFERENCE constraint"; an INSERT/UPDATE pointing at a
        // missing parent says "conflicted with the FOREIGN KEY constraint". Checking
        // "reference constraint" first matters — it is the more specific phrase.
        if (lower.contains("reference constraint")) {
            return build(ErrorCode.REFERENCED_BY_OTHER_RECORDS, null, request);
        }
        if (lower.contains("foreign key constraint")) {
            return build(ErrorCode.INVALID_REFERENCE, null, request);
        }

        return build(mapConstraint(lower), null, request);
    }

    /** Maps a unique constraint name onto a specific, user-facing code. */
    private ErrorCode mapConstraint(String message) {
        if (message.contains("uq_attr_code")) {
            return ErrorCode.ATTRIBUTE_CODE_EXISTS;
        }
        if (message.contains("uq_var_sku") || message.contains("ux_var_barcode")) {
            return message.contains("barcode")
                    ? ErrorCode.BARCODE_ALREADY_EXISTS : ErrorCode.SKU_ALREADY_EXISTS;
        }
        if (message.contains("uq_prod_slug") || message.contains("uq_cat_slug")
                || message.contains("uq_brand_slug")) {
            return ErrorCode.SLUG_ALREADY_EXISTS;
        }
        if (message.contains("ux_user_email")) {
            return ErrorCode.EMAIL_ALREADY_EXISTS;
        }
        if (message.contains("ux_user_phone")) {
            return ErrorCode.PHONE_ALREADY_EXISTS;
        }
        if (message.contains("uq_av_code")) {
            return ErrorCode.ATTRIBUTE_VALUE_CODE_EXISTS;
        }
        return ErrorCode.DUPLICATE_VALUE;
    }

    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ProblemDetail handleOptimisticLock(OptimisticLockingFailureException ex,
                                              HttpServletRequest request) {
        log.warn("Optimistic lock conflict on {}", request.getRequestURI());
        return build(ErrorCode.CONCURRENT_STOCK_CHANGE, null, request);
    }

    // ------------------------------------------------------------------- files

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ProblemDetail handleUploadTooLarge(MaxUploadSizeExceededException ex,
                                              HttpServletRequest request) {
        return build(ErrorCode.FILE_TOO_LARGE, null, request);
    }

    // -------------------------------------------------------------- security

    @ExceptionHandler(AccessDeniedException.class)
    public ProblemDetail handleAccessDenied(AccessDeniedException ex,
                                            HttpServletRequest request) {
        return build(ErrorCode.FORBIDDEN, null, request);
    }

    @ExceptionHandler(AuthenticationException.class)
    public ProblemDetail handleAuthentication(AuthenticationException ex,
                                              HttpServletRequest request) {
        return build(ErrorCode.UNAUTHORIZED, null, request);
    }

    // --------------------------------------------------------------- routing

    @ExceptionHandler(NoHandlerFoundException.class)
    public ProblemDetail handleNoHandler(NoHandlerFoundException ex,
                                         HttpServletRequest request) {
        return build(ErrorCode.RESOURCE_NOT_FOUND, "No endpoint matches this path", request);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ProblemDetail handleMethodNotSupported(HttpRequestMethodNotSupportedException ex,
                                                  HttpServletRequest request) {
        return build(ErrorCode.METHOD_NOT_ALLOWED,
                "%s is not supported here. Supported: %s"
                        .formatted(ex.getMethod(), String.join(", ",
                                ex.getSupportedMethods() == null
                                        ? new String[]{} : ex.getSupportedMethods())),
                request);
    }

    // ------------------------------------------------------------- catch-all

    /**
     * Anything unplanned. Logged at ERROR with the stack trace, but the client is
     * told nothing about internals — exception messages leak table names, file
     * paths and query structure.
     */
    @ExceptionHandler(Exception.class)
    public ProblemDetail handleUnexpected(Exception ex, HttpServletRequest request) {
        log.error("Unhandled exception on {}", request.getRequestURI(), ex);
        return build(ErrorCode.INTERNAL_ERROR, null, request);
    }

    // ------------------------------------------------------------------ helpers

    private ProblemDetail build(ErrorCode code, String detail, HttpServletRequest request) {
        HttpStatus status = code.getStatus();
        ProblemDetail problem = ProblemDetail.forStatus(status);
        problem.setType(URI.create(ERROR_BASE + code.name().toLowerCase().replace('_', '-')));
        problem.setTitle(status.getReasonPhrase());
        problem.setDetail(detail != null ? detail : code.getDefaultMessage());
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setProperty("code", code.name());
        problem.setProperty("timestamp", OffsetDateTime.now().toString());
        return problem;
    }

    private String rootMessage(Throwable ex) {
        Throwable current = ex;
        int guard = 0;
        while (current.getCause() != null && guard++ < 10) {
            current = current.getCause();
        }
        return current.getMessage() == null ? "" : current.getMessage();
    }

    /** One invalid field, as reported to the client. */
    public record FieldErrorDetail(String field, String message) {
    }
}
