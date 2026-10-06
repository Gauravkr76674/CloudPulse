package com.cloudops.agent.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.cloudops.agent.entity.AuditLog;
import com.cloudops.agent.entity.AuditOutcome;
import com.cloudops.agent.entity.Role;
import com.cloudops.agent.repository.AuditLogRepository;
import com.cloudops.agent.security.CurrentUser;
import com.cloudops.agent.util.Texts;

/**
 * Writes the append-only audit trail.
 *
 * Every write uses {@code Propagation.REQUIRES_NEW} so an audit row survives even when
 * the surrounding business transaction rolls back — a denied or failed action is exactly
 * what you most want recorded. Audit failures are swallowed and logged: bookkeeping must
 * never break the operation it is describing.
 */
@Service
public class AuditService {

    private static final Logger log = LoggerFactory.getLogger(AuditService.class);
    private static final int DETAILS_MAX = 4000;

    private final AuditLogRepository auditLogRepository;

    public AuditService(AuditLogRepository auditLogRepository) {
        this.auditLogRepository = auditLogRepository;
    }

    public void success(String action, String targetType, Object targetId, String details) {
        record(action, targetType, targetId, AuditOutcome.SUCCESS, details);
    }

    public void failure(String action, String targetType, Object targetId, String details) {
        record(action, targetType, targetId, AuditOutcome.FAILURE, details);
    }

    /** Guardrail refusals: unsafe action, missing approval, rate limit, kill switch. */
    public void denied(String action, String targetType, Object targetId, String details) {
        record(action, targetType, targetId, AuditOutcome.DENIED, details);
    }

    /** Records an entry for an explicit username (used by login, where no context exists yet). */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordAs(String username, Role role, String action, String targetType,
            Object targetId, AuditOutcome outcome, String details) {
        try {
            AuditLog entry = new AuditLog();
            entry.setUsername(Texts.truncate(Texts.defaultIfBlank(username, CurrentUser.SYSTEM), 60));
            entry.setRole(role);
            entry.setAction(Texts.truncate(action, 60));
            entry.setTargetType(Texts.truncate(targetType, 40));
            entry.setTargetId(targetId == null ? null : Texts.truncate(String.valueOf(targetId), 60));
            entry.setOutcome(outcome);
            // Redaction is a safety net: details should never contain a secret in the first place
            entry.setDetails(Texts.truncate(Texts.redactSecrets(details), DETAILS_MAX));
            entry.setIpAddress(Texts.truncate(CurrentUser.ipAddress(), 60));
            auditLogRepository.save(entry);
        } catch (Exception ex) {
            log.error("Failed to write audit entry for action {}: {}", action, ex.getMessage());
        }
    }

    private void record(String action, String targetType, Object targetId, AuditOutcome outcome,
            String details) {
        recordAs(CurrentUser.username(), CurrentUser.role().orElse(null), action, targetType,
                targetId, outcome, details);
    }

    @Transactional(readOnly = true)
    public Page<AuditLog> recent(Pageable pageable) {
        return auditLogRepository.findAllByOrderByCreatedAtDesc(pageable);
    }
}
