package com.cloudops.agent.service;

import java.time.Instant;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.cloudops.agent.entity.ActionExecution;
import com.cloudops.agent.entity.Incident;
import com.cloudops.agent.entity.Notification;
import com.cloudops.agent.entity.NotificationType;
import com.cloudops.agent.entity.Recommendation;
import com.cloudops.agent.entity.Severity;
import com.cloudops.agent.entity.User;
import com.cloudops.agent.exception.NotFoundException;
import com.cloudops.agent.repository.NotificationRepository;
import com.cloudops.agent.repository.UserRepository;
import com.cloudops.agent.util.Texts;

/**
 * In-app notifications — the agent's way of telling a human that something needs them.
 *
 * Everything the pipeline raises is a broadcast (visible to all signed-in users) because
 * the MVP has no on-call rota; personal notifications are supported by the schema and by
 * {@link #toUser(String, NotificationType, Severity, String, String, Incident)} for when
 * one is added.
 *
 * Notification failures are never allowed to break the operation that triggered them: a
 * missed toast is a nuisance, a rolled-back remediation is an outage.
 */
@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);
    private static final int DEFAULT_LIMIT = 50;

    private final NotificationRepository notificationRepository;
    private final UserRepository userRepository;

    public NotificationService(NotificationRepository notificationRepository,
                               UserRepository userRepository) {
        this.notificationRepository = notificationRepository;
        this.userRepository = userRepository;
    }

    // ---------------------------------------------------------------------
    // pipeline events
    // ---------------------------------------------------------------------

    public void incidentOpened(Incident incident) {
        broadcast(NotificationType.INCIDENT_OPENED,
                incident.getSeverity(),
                "New " + incident.getSeverity() + " incident: " + incident.getTitle(),
                String.format("%s on %s reached %s (threshold %s). Detected by rule %s.",
                        incident.getMetricName(),
                        incident.getResource().getName(),
                        Texts.number(incident.getTriggerValue()),
                        Texts.number(incident.getThresholdValue()),
                        incident.getRuleName()),
                incident);
    }

    public void severityEscalated(Incident incident, Severity previous) {
        broadcast(NotificationType.INCIDENT_OPENED,
                incident.getSeverity(),
                "Incident #" + incident.getId() + " escalated to " + incident.getSeverity(),
                String.format("Severity moved from %s to %s after %d consecutive breaches of %s.",
                        previous, incident.getSeverity(), incident.getOccurrenceCount(),
                        incident.getMetricName()),
                incident);
    }

    public void analysisReady(Incident incident, String summary) {
        broadcast(NotificationType.AI_ANALYSIS_READY,
                incident.getSeverity(),
                "AI analysis ready for incident #" + incident.getId(),
                Texts.defaultIfBlank(summary, "The AI pipeline finished analysing this incident."),
                incident);
    }

    public void approvalRequired(Incident incident, Recommendation recommendation) {
        String action = recommendation.getActionType() == null
                ? "an action"
                : recommendation.getActionType().getDisplayName();
        broadcast(NotificationType.APPROVAL_REQUIRED,
                Severity.HIGH,
                "Approval required: " + action + " on " + incident.getResource().getName(),
                String.format("%s%nRisk: %s. An administrator must approve before anything runs.",
                        Texts.defaultIfBlank(recommendation.getTitle(), action),
                        recommendation.getRiskLevel()),
                incident);
    }

    public void unsafeRecommendationRejected(Incident incident, String rawAction) {
        broadcast(NotificationType.SYSTEM,
                Severity.MEDIUM,
                "Unsafe AI recommendation blocked on incident #" + incident.getId(),
                "The model proposed \"" + Texts.truncate(rawAction, 120) + "\", which is not in the "
                        + "safe action catalog. It was stored as evidence and never executed.",
                incident);
    }

    public void actionExecuted(ActionExecution action) {
        broadcast(NotificationType.ACTION_EXECUTED,
                Severity.MEDIUM,
                (action.isDryRun() ? "Dry-run: " : "Executed: ")
                        + action.getActionType().getDisplayName() + " on " + action.getResource().getName(),
                Texts.defaultIfBlank(action.getResultMessage(), "Action completed."),
                action.getIncident());
    }

    public void actionFailed(ActionExecution action) {
        broadcast(NotificationType.ACTION_FAILED,
                Severity.HIGH,
                "Action failed: " + action.getActionType().getDisplayName()
                        + " on " + action.getResource().getName(),
                Texts.defaultIfBlank(action.getErrorMessage(), "The provider call did not succeed."),
                action.getIncident());
    }

    public void actionBlocked(ActionExecution action) {
        broadcast(NotificationType.ACTION_BLOCKED,
                Severity.HIGH,
                "Action blocked by a guardrail: " + action.getActionType().getDisplayName(),
                Texts.defaultIfBlank(action.getBlockedReason(), "A safety check refused this action."),
                action.getIncident());
    }

    public void incidentResolved(Incident incident) {
        broadcast(NotificationType.INCIDENT_RESOLVED,
                Severity.LOW,
                "Incident #" + incident.getId() + " resolved",
                Texts.defaultIfBlank(incident.getResolutionNote(),
                        "The incident was marked resolved by " + incident.getResolvedBy() + "."),
                incident);
    }

    // ---------------------------------------------------------------------
    // writes
    // ---------------------------------------------------------------------

    /** Visible to every signed-in user. */
    @Transactional
    public void broadcast(NotificationType type, Severity severity, String title, String message,
                          Incident incident) {
        try {
            Notification notification = new Notification();
            notification.setBroadcast(true);
            notification.setRecipient(null);
            notification.setType(type);
            notification.setSeverity(severity == null ? Severity.LOW : severity);
            notification.setTitle(Texts.truncate(title, 200));
            notification.setMessage(message);
            notification.setIncident(incident);
            notificationRepository.save(notification);
        } catch (Exception ex) {
            // Never let a notification failure break the caller's work
            log.warn("Could not store {} notification: {}", type, ex.getMessage());
        }
    }

    /** Visible only to one user. */
    @Transactional
    public void toUser(String username, NotificationType type, Severity severity, String title,
                       String message, Incident incident) {
        try {
            User recipient = userRepository.findByUsername(username).orElse(null);
            if (recipient == null) {
                log.debug("No user {} for notification {}; storing as broadcast instead", username, type);
                broadcast(type, severity, title, message, incident);
                return;
            }
            Notification notification = new Notification();
            notification.setBroadcast(false);
            notification.setRecipient(recipient);
            notification.setType(type);
            notification.setSeverity(severity == null ? Severity.LOW : severity);
            notification.setTitle(Texts.truncate(title, 200));
            notification.setMessage(message);
            notification.setIncident(incident);
            notificationRepository.save(notification);
        } catch (Exception ex) {
            log.warn("Could not store personal {} notification: {}", type, ex.getMessage());
        }
    }

    // ---------------------------------------------------------------------
    // reads
    // ---------------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<Notification> listFor(String username, boolean unreadOnly, Integer limit) {
        User user = requireUser(username);
        Pageable pageable = PageRequest.of(0, limit == null || limit <= 0 ? DEFAULT_LIMIT : Math.min(limit, 200));
        return unreadOnly
                ? notificationRepository.findUnreadFor(user, pageable)
                : notificationRepository.findVisibleTo(user, pageable);
    }

    @Transactional(readOnly = true)
    public long countUnread(String username) {
        return notificationRepository.countUnreadFor(requireUser(username));
    }

    @Transactional
    public int markAllRead(String username) {
        return notificationRepository.markAllReadFor(requireUser(username), Instant.now());
    }

    @Transactional
    public Notification markRead(Long id, String username) {
        Notification notification = notificationRepository.findById(id)
                .orElseThrow(() -> NotFoundException.of("Notification", id));

        boolean visible = notification.isBroadcast()
                || (notification.getRecipient() != null
                        && notification.getRecipient().getUsername().equals(username));
        if (!visible) {
            // Do not reveal that the id exists for someone else
            throw NotFoundException.of("Notification", id);
        }

        if (!notification.isRead()) {
            notification.setRead(true);
            notification.setReadAt(Instant.now());
            notificationRepository.save(notification);
        }
        return notification;
    }

    private User requireUser(String username) {
        return userRepository.findByUsername(username)
                .orElseThrow(() -> NotFoundException.of("User", username));
    }
}
