package com.velora.api.audit.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.velora.api.audit.repository.AuditLogRepository;
import com.velora.api.common.exception.BusinessException;
import com.velora.api.common.exception.ErrorCode;
import com.velora.api.identity.repository.AppUserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;

/**
 * {@code GET /admin/audit-log?action=<bad value>} used to 500: {@code list()} called
 * {@code AuditAction.valueOf()} directly, and the resulting bare
 * {@code IllegalArgumentException} skipped every {@code BusinessException} mapping in
 * {@code GlobalExceptionHandler}.
 */
class AuditServiceTest {

    private final AuditLogRepository auditLogRepository = mock(AuditLogRepository.class);
    private final AppUserRepository userRepository = mock(AppUserRepository.class);
    private final AuditService auditService = new AuditService(auditLogRepository, userRepository);

    @Test
    @DisplayName("An unrecognised action filter is a 400, not a 500, and never reaches the repository")
    void invalidActionFilterIsABusinessException() {
        assertThatThrownBy(() -> auditService.list(
                "NOT_A_REAL_ACTION", null, null, null, PageRequest.of(0, 25)))
                .isInstanceOf(BusinessException.class)
                .isNotInstanceOf(IllegalArgumentException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_PARAMETER);

        verifyNoInteractions(auditLogRepository);
    }
}
