package com.velora.api.audit.service;

import com.velora.api.audit.domain.AuditAction;
import com.velora.api.audit.domain.AuditLog;
import com.velora.api.audit.dto.AuditLogResponse;
import com.velora.api.audit.repository.AuditLogRepository;
import com.velora.api.common.dto.PageResponse;
import com.velora.api.identity.repository.AppUserRepository;
import java.time.OffsetDateTime;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Records staff actions.
 *
 * <p>Writes run in {@code REQUIRES_NEW}: an audit entry commits on its own, so a
 * later failure in the business transaction cannot erase the record that someone
 * tried. The inverse — a failed audit write silently rolling back a legitimate price
 * change — would be worse, so failures here are logged and swallowed.
 *
 * <p>The trade-off is deliberate. The log is evidence, not a control: it must never
 * be the reason a valid operation fails.
 */
@Service
public class AuditService {

    private static final Logger log = LoggerFactory.getLogger(AuditService.class);

    private final AuditLogRepository auditLogRepository;
    private final AppUserRepository userRepository;

    public AuditService(AuditLogRepository auditLogRepository,
                        AppUserRepository userRepository) {
        this.auditLogRepository = auditLogRepository;
        this.userRepository = userRepository;
    }

    /**
     * @param entityLabel a human label — SKU, invoice number, product name. Stored so
     *                    the log reads on its own, without joining tables that may no
     *                    longer contain the row.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(AuditAction action, String entityType, Object entityId,
                       String entityLabel, Object oldValue, Object newValue,
                       String reason, Long actorId) {
        try {
            AuditLog entry = new AuditLog();
            entry.setAction(action);
            entry.setEntityType(entityType);
            entry.setEntityId(entityId == null ? null : String.valueOf(entityId));
            entry.setEntityLabel(truncate(entityLabel, 200));
            entry.setOldValue(truncate(stringify(oldValue), 500));
            entry.setNewValue(truncate(stringify(newValue), 500));
            entry.setReason(truncate(reason, 500));
            entry.setActorId(actorId);
            entry.setActorName(resolveActorName(actorId));

            auditLogRepository.save(entry);

        } catch (Exception ex) {
            // Never let bookkeeping break the operation it was recording.
            log.error("Could not write audit entry {} on {} {}",
                    action, entityType, entityId, ex);
        }
    }

    /** Shorthand for the common case: a single field changing value. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordChange(AuditAction action, String entityType, Object entityId,
                             String entityLabel, Object from, Object to, Long actorId) {
        record(action, entityType, entityId, entityLabel, from, to, null, actorId);
    }

    // ------------------------------------------------------------------- read

    @Transactional(readOnly = true)
    public PageResponse<AuditLogResponse> list(String action, Long actorId,
                                               String entityType, String entityId,
                                               Pageable pageable) {
        Page<AuditLog> page;

        if (entityType != null && entityId != null) {
            page = auditLogRepository.findByEntityTypeAndEntityIdOrderByCreatedAtDesc(
                    entityType, entityId, pageable);
        } else if (action != null && !action.isBlank()) {
            page = auditLogRepository.findByActionOrderByCreatedAtDesc(
                    AuditAction.valueOf(action.toUpperCase()), pageable);
        } else if (actorId != null) {
            page = auditLogRepository.findByActorIdOrderByCreatedAtDesc(actorId, pageable);
        } else {
            page = auditLogRepository.findAllByOrderByCreatedAtDesc(pageable);
        }

        return PageResponse.from(page, this::toResponse);
    }

    /**
     * Resolves the human-readable name for an actor, at the moment of the call —
     * copy the result onto the row you are writing rather than storing just the id,
     * or the label will drift if the account is later renamed or removed. Shared by
     * every ledger that needs a "who did this" column (audit log, stock movements),
     * so the "first + last name, else user#id, else system" rule stays in one place.
     */
    public String resolveActorName(Long actorId) {
        if (actorId == null) {
            return "system";
        }
        return userRepository.findById(actorId)
                .map(user -> {
                    String first = Optional.ofNullable(user.getFirstName()).orElse("");
                    String last = Optional.ofNullable(user.getLastName()).orElse("");
                    String name = (first + " " + last).trim();
                    return name.isEmpty() ? ("user#" + actorId) : name;
                })
                .orElse("user#" + actorId);
    }

    private String stringify(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    private AuditLogResponse toResponse(AuditLog entry) {
        return new AuditLogResponse(
                entry.getId(),
                entry.getAction().name(),
                entry.getEntityType(),
                entry.getEntityId(),
                entry.getEntityLabel(),
                entry.getOldValue(),
                entry.getNewValue(),
                entry.getReason(),
                entry.getActorId(),
                entry.getActorName(),
                entry.getCreatedAt());
    }

    /** Used by the dashboard to show recent activity. */
    @Transactional(readOnly = true)
    public Page<AuditLog> recent(Pageable pageable) {
        return auditLogRepository.findAllByOrderByCreatedAtDesc(pageable);
    }

    @Transactional(readOnly = true)
    public PageResponse<AuditLogResponse> between(OffsetDateTime from, OffsetDateTime to,
                                                  Pageable pageable) {
        return PageResponse.from(
                auditLogRepository.findBetween(from, to, pageable), this::toResponse);
    }
}
