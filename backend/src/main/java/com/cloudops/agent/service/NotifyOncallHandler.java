package com.cloudops.agent.service;

import org.springframework.stereotype.Component;

import com.cloudops.agent.aws.model.ProviderActionResult;
import com.cloudops.agent.entity.ActionType;
import com.cloudops.agent.entity.Incident;
import com.cloudops.agent.entity.NotificationType;
import com.cloudops.agent.entity.Severity;
import com.cloudops.agent.repository.IncidentRepository;

/**
 * NOTIFY_ONCALL — raises a high-priority in-app notification. Makes no cloud API call at all,
 * which is why it is the safe default when the AI is unsure what to do.
 */
@Component
public class NotifyOncallHandler implements ActionHandler {

    private final NotificationService notificationService;
    private final IncidentRepository incidentRepository;

    public NotifyOncallHandler(NotificationService notificationService,
                              IncidentRepository incidentRepository) {
        this.notificationService = notificationService;
        this.incidentRepository = incidentRepository;
    }

    @Override
    public ActionType actionType() {
        return ActionType.NOTIFY_ONCALL;
    }

    @Override
    public ProviderActionResult execute(Target target) {
        Incident incident = incidentRepository.findById(target.incidentId()).orElse(null);
        notificationService.broadcast(
                NotificationType.APPROVAL_REQUIRED,
                Severity.HIGH,
                "On-call escalation for " + target.resourceName(),
                "The agent escalated incident #" + target.incidentId() + " on "
                        + target.resourceName() + " (" + target.providerResourceId()
                        + ") for human attention. No change was made to the instance.",
                incident);
        return ProviderActionResult.ok("On-call notification raised.", null);
    }
}
