package com.velora.api.common.exception;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

/**
 * {@code DataIntegrityViolationException} used to collapse every SQL Server
 * constraint failure into {@code DUPLICATE_VALUE} ("This value is already in use"),
 * which is actively misleading for a NOT NULL violation or a bad foreign key — see
 * the incident on {@code POST /api/v1/me/addresses}: a NOT NULL column the request
 * legitimately never populates reported itself to the client as a duplicate-value
 * conflict, which sent debugging in the wrong direction entirely.
 */
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    @DisplayName("NOT NULL violation names the empty column instead of claiming a duplicate")
    void notNullViolation_namesTheColumn() {
        var ex = new DataIntegrityViolationException(
                "Cannot insert the value NULL into column 'city_id', "
                        + "table 'velora.dbo.customer_address'; column does not allow nulls. "
                        + "INSERT fails.");

        ProblemDetail problem = handler.handleDataIntegrity(ex, requestTo("/api/v1/me/addresses"));

        assertThat(problem.getProperties().get("code")).isEqualTo("REQUIRED_FIELD_MISSING");
        assertThat(problem.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST.value());
        assertThat(problem.getDetail()).contains("city_id");
    }

    @Test
    @DisplayName("An INSERT pointing at a missing parent row is not a duplicate value")
    void foreignKeyOnInsert_isNotADuplicate() {
        var ex = new DataIntegrityViolationException(
                "The INSERT statement conflicted with the FOREIGN KEY constraint "
                        + "\"fk_addr_city\". The conflict occurred in database \"velora\", "
                        + "table \"dbo.city\", column 'id'.");

        ProblemDetail problem = handler.handleDataIntegrity(ex, requestTo("/api/v1/me/addresses"));

        assertThat(problem.getProperties().get("code")).isEqualTo("INVALID_REFERENCE");
        assertThat(problem.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST.value());
    }

    @Test
    @DisplayName("A DELETE blocked by child rows stays REFERENCED_BY_OTHER_RECORDS")
    void foreignKeyOnDelete_isReferencedByOtherRecords() {
        var ex = new DataIntegrityViolationException(
                "The DELETE statement conflicted with the REFERENCE constraint "
                        + "\"fk_var_product\". The conflict occurred in database \"velora\", "
                        + "table \"dbo.product_variant\", column 'product_id'.");

        ProblemDetail problem = handler.handleDataIntegrity(ex, requestTo("/api/v1/admin/products/1"));

        assertThat(problem.getProperties().get("code")).isEqualTo("REFERENCED_BY_OTHER_RECORDS");
        assertThat(problem.getStatus()).isEqualTo(HttpStatus.CONFLICT.value());
    }

    @Test
    @DisplayName("An actual unique index violation still maps to its specific code")
    void uniqueConstraint_mapsToItsSpecificCode() {
        var ex = new DataIntegrityViolationException(
                "Violation of UNIQUE KEY constraint 'ux_user_email'. Cannot insert duplicate "
                        + "key in object 'dbo.app_user'.");

        ProblemDetail problem = handler.handleDataIntegrity(ex, requestTo("/api/v1/auth/register"));

        assertThat(problem.getProperties().get("code")).isEqualTo("EMAIL_ALREADY_EXISTS");
        assertThat(problem.getStatus()).isEqualTo(HttpStatus.CONFLICT.value());
    }

    @Test
    @DisplayName("An unrecognised unique index still falls back to DUPLICATE_VALUE")
    void unrecognisedUniqueConstraint_fallsBackToDuplicateValue() {
        var ex = new DataIntegrityViolationException(
                "Violation of UNIQUE KEY constraint 'ux_some_new_index'. Cannot insert "
                        + "duplicate key in object 'dbo.some_table'.");

        ProblemDetail problem = handler.handleDataIntegrity(ex, requestTo("/api/v1/whatever"));

        assertThat(problem.getProperties().get("code")).isEqualTo("DUPLICATE_VALUE");
        assertThat(problem.getStatus()).isEqualTo(HttpStatus.CONFLICT.value());
    }

    /**
     * {@code IllegalArgumentException} — the exception {@code Enum.valueOf()} throws
     * — used to have no explicit mapping here, so it fell through to the generic
     * {@code Exception} handler and came back as a 500 with no useful detail. This
     * is the safety net for that: everything that reaches this handler is a 400 that
     * at least echoes the exception's own message, never a blank "unexpected error".
     */
    @Test
    @DisplayName("IllegalArgumentException becomes a 400 INVALID_PARAMETER, not a 500")
    void illegalArgumentBecomesFourHundred() {
        ProblemDetail problem = handler.handleIllegalArgument(
                new IllegalArgumentException("No enum constant ...AuditAction.BOGUS"),
                requestTo("/api/v1/admin/audit-log"));

        assertThat(problem.getProperties().get("code")).isEqualTo("INVALID_PARAMETER");
        assertThat(problem.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST.value());
        assertThat(problem.getDetail()).contains("BOGUS");
    }

    private HttpServletRequest requestTo(String uri) {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRequestURI()).thenReturn(uri);
        return request;
    }
}
