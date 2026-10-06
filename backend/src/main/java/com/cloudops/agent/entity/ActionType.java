package com.cloudops.agent.entity;

import java.util.Optional;

/**
 * THE SAFETY WHITELIST.
 *
 * The AI layer may only ever name one of these constants. Anything else that comes
 * back from a model is rejected before it reaches the database, and nothing here maps
 * to a shell command - each constant maps to exactly one predefined AWS SDK call
 * (see the ActionHandler implementations in com.cloudops.agent.service).
 */
public enum ActionType {

    REBOOT_EC2_INSTANCE(
            "Reboot EC2 instance",
            true, true, RiskLevel.MEDIUM,
            "Calls ec2:RebootInstances on the affected instance."),

    STOP_EC2_INSTANCE(
            "Stop EC2 instance",
            true, true, RiskLevel.HIGH,
            "Calls ec2:StopInstances. Use for runaway or compromised instances."),

    START_EC2_INSTANCE(
            "Start EC2 instance",
            true, true, RiskLevel.LOW,
            "Calls ec2:StartInstances on a stopped instance."),

    TAG_FOR_REVIEW(
            "Tag instance for human review",
            true, true, RiskLevel.LOW,
            "Calls ec2:CreateTags to add CloudOpsReview=<incident key>. Non-disruptive."),

    NOTIFY_ONCALL(
            "Notify on-call",
            false, false, RiskLevel.LOW,
            "Creates a high-priority in-app notification for operators. No AWS call."),

    COLLECT_MORE_METRICS(
            "Collect additional metrics",
            false, false, RiskLevel.LOW,
            "Re-polls CloudWatch for the affected resource to confirm the signal."),

    RECOMMEND_MANUAL_SCALE_UP(
            "Recommend manual scale-up (advisory only)",
            false, false, RiskLevel.LOW,
            "Records advice to resize the instance. Deliberately NOT automated: "
                    + "changing instance type can leave the free tier."),

    NO_ACTION(
            "No action required",
            false, false, RiskLevel.LOW,
            "Records that the anomaly is benign or self-recovering.");

    private final String displayName;
    /** true when executing this action changes state in AWS. */
    private final boolean mutating;
    /** true when an ADMIN must approve before execution. */
    private final boolean approvalRequired;
    private final RiskLevel defaultRisk;
    private final String description;

    ActionType(String displayName, boolean mutating, boolean approvalRequired,
               RiskLevel defaultRisk, String description) {
        this.displayName = displayName;
        this.mutating = mutating;
        this.approvalRequired = approvalRequired;
        this.defaultRisk = defaultRisk;
        this.description = description;
    }

    public String getDisplayName() {
        return displayName;
    }

    public boolean isMutating() {
        return mutating;
    }

    public boolean isApprovalRequired() {
        return approvalRequired;
    }

    public RiskLevel getDefaultRisk() {
        return defaultRisk;
    }

    public String getDescription() {
        return description;
    }

    /** Only EC2 actions require an EC2 resource; used by the pre-execution validator. */
    public boolean requiresEc2Resource() {
        return this == REBOOT_EC2_INSTANCE
                || this == STOP_EC2_INSTANCE
                || this == START_EC2_INSTANCE
                || this == TAG_FOR_REVIEW;
    }

    /**
     * Lenient lookup used when parsing AI output: tolerates lower case, spaces and
     * dashes, but never invents a value. Unknown text yields an empty Optional and
     * the recommendation is stored as REJECTED_UNSAFE.
     */
    public static Optional<ActionType> parseSafely(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        String normalized = raw.trim().toUpperCase().replace(' ', '_').replace('-', '_');
        for (ActionType type : values()) {
            if (type.name().equals(normalized)) {
                return Optional.of(type);
            }
        }
        return Optional.empty();
    }
}
