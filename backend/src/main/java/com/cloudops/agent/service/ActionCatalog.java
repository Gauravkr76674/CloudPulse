package com.cloudops.agent.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Component;

import com.cloudops.agent.dto.ActionCatalogEntryDto;
import com.cloudops.agent.entity.ActionType;
import com.cloudops.agent.entity.CloudResource;
import com.cloudops.agent.entity.ResourceStatus;
import com.cloudops.agent.entity.ResourceType;
import com.cloudops.agent.util.DtoMapper;

/**
 * The single source of truth for "what may the agent do, and to what".
 *
 * Both halves of the safety story read from here:
 * <ul>
 *   <li>the AI prompt is built from {@link #allowedNamesFor(CloudResource)}, so the model is
 *       handed a closed list instead of being asked to invent a command;</li>
 *   <li>the executor re-checks {@link #rejectionReason(ActionType, CloudResource)} at
 *       execution time, because the resource may have changed state between the
 *       recommendation and the approval.</li>
 * </ul>
 *
 * Checking twice is deliberate. The model's output is untrusted input; the catalog is the
 * boundary that turns it into either a known enum constant or a rejection.
 */
@Component
public class ActionCatalog {

    /**
     * Actions offered for a specific resource, filtered by what actually makes sense for
     * its current state — there is no point proposing a reboot of a stopped instance.
     */
    public List<ActionType> allowedFor(CloudResource resource) {
        List<ActionType> allowed = new ArrayList<>();
        for (ActionType type : ActionType.values()) {
            if (rejectionReason(type, resource).isEmpty()) {
                allowed.add(type);
            }
        }
        return allowed;
    }

    /** The same list as plain names, which is what goes into the prompt. */
    public List<String> allowedNamesFor(CloudResource resource) {
        return allowedFor(resource).stream().map(Enum::name).toList();
    }

    /**
     * Maps untrusted model text to a catalog constant. Anything unrecognised yields an
     * empty Optional and the recommendation is stored as REJECTED_UNSAFE — this is the
     * method that makes "AI writes an AWS CLI command" structurally impossible.
     */
    public Optional<ActionType> resolve(String rawAction) {
        return ActionType.parseSafely(rawAction);
    }

    /**
     * Why this action may not run against this resource, or empty when it may.
     *
     * @return a human-readable reason suitable for {@code blockedReason} / an audit entry
     */
    public Optional<String> rejectionReason(ActionType type, CloudResource resource) {
        if (type == null) {
            return Optional.of("No action type was resolved from the recommendation.");
        }
        if (resource == null) {
            return Optional.of("The action has no target resource.");
        }

        if (type.requiresEc2Resource() && resource.getType() != ResourceType.EC2_INSTANCE) {
            return Optional.of(type.name() + " only applies to EC2 instances, but "
                    + resource.getName() + " is a " + resource.getType() + ".");
        }

        ResourceStatus status = resource.getStatus();
        switch (type) {
            case REBOOT_EC2_INSTANCE, STOP_EC2_INSTANCE -> {
                if (status == ResourceStatus.STOPPED || status == ResourceStatus.TERMINATED) {
                    return Optional.of(type.name() + " needs a running instance, but "
                            + resource.getName() + " is " + status + ".");
                }
            }
            case START_EC2_INSTANCE -> {
                if (status == ResourceStatus.RUNNING) {
                    return Optional.of("START_EC2_INSTANCE is pointless: " + resource.getName()
                            + " is already running.");
                }
                if (status == ResourceStatus.TERMINATED) {
                    return Optional.of("A terminated instance cannot be started.");
                }
            }
            case TAG_FOR_REVIEW -> {
                if (status == ResourceStatus.TERMINATED) {
                    return Optional.of("A terminated instance cannot be tagged.");
                }
            }
            default -> {
                // Advisory actions (notify, collect metrics, advise scale-up, no action)
                // are always available: none of them changes anything in AWS.
            }
        }
        return Optional.empty();
    }

    /**
     * The full catalog for the UI, so an approver can read exactly what each action does
     * before allowing it. {@code executable = false} marks actions the agent will never
     * perform automatically, only advise.
     */
    public List<ActionCatalogEntryDto> catalog() {
        List<ActionCatalogEntryDto> entries = new ArrayList<>();
        for (ActionType type : ActionType.values()) {
            entries.add(DtoMapper.toCatalogEntry(type, isAutomatable(type), executionNote(type)));
        }
        return entries;
    }

    /** True when an approved recommendation of this type is actually carried out by the agent. */
    public boolean isAutomatable(ActionType type) {
        return type != ActionType.RECOMMEND_MANUAL_SCALE_UP;
    }

    private String executionNote(ActionType type) {
        return switch (type) {
            case REBOOT_EC2_INSTANCE -> "One ec2:RebootInstances call. Requires ADMIN approval; "
                    + "blocked when the instance is not running.";
            case STOP_EC2_INSTANCE -> "One ec2:StopInstances call. Highest-risk action in the "
                    + "catalog — requires ADMIN approval and is rate limited like the rest.";
            case START_EC2_INSTANCE -> "One ec2:StartInstances call. Only offered when the "
                    + "instance is stopped.";
            case TAG_FOR_REVIEW -> "One ec2:CreateTags call adding CloudOpsReview and "
                    + "CloudOpsIncident tags. Nothing about the workload changes.";
            case NOTIFY_ONCALL -> "Creates a HIGH severity in-app notification. No AWS call.";
            case COLLECT_MORE_METRICS -> "Re-polls CloudWatch for this resource immediately "
                    + "instead of waiting for the next scheduled pass.";
            case RECOMMEND_MANUAL_SCALE_UP -> "Advisory only. Resizing an instance can move it "
                    + "off the free tier, so the agent records the advice and stops there.";
            case NO_ACTION -> "Records that the anomaly needs no intervention.";
        };
    }
}
