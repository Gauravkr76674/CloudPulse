package com.cloudops.agent.service;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.cloudops.agent.ai.AiService;
import com.cloudops.agent.aws.CloudProvider;
import com.cloudops.agent.aws.model.PermissionCheck;
import com.cloudops.agent.aws.model.ProviderIdentity;
import com.cloudops.agent.config.AiProperties;
import com.cloudops.agent.config.CloudProperties;
import com.cloudops.agent.config.MonitoringProperties;
import com.cloudops.agent.config.RemediationProperties;
import com.cloudops.agent.dto.SystemStatusDto;
import com.cloudops.agent.util.Texts;

/**
 * "Is this thing actually wired up?" — one place that answers it for the System page and for
 * the startup log.
 *
 * Everything here is derived from live configuration and a real identity call to the
 * provider, so the page cannot claim AWS is connected when it is not. No secret is ever
 * returned: {@link #status()} reports whether a key is <em>present</em>, never its value.
 */
@Service
public class SystemService {

    private static final Logger log = LoggerFactory.getLogger(SystemService.class);

    private final CloudProvider cloudProvider;
    private final AiService aiService;
    private final CloudProperties cloudProperties;
    private final AiProperties aiProperties;
    private final MonitoringProperties monitoringProperties;
    private final RemediationProperties remediationProperties;

    public SystemService(CloudProvider cloudProvider,
                        AiService aiService,
                        CloudProperties cloudProperties,
                        AiProperties aiProperties,
                        MonitoringProperties monitoringProperties,
                        RemediationProperties remediationProperties) {
        this.cloudProvider = cloudProvider;
        this.aiService = aiService;
        this.cloudProperties = cloudProperties;
        this.aiProperties = aiProperties;
        this.monitoringProperties = monitoringProperties;
        this.remediationProperties = remediationProperties;
    }

    public SystemStatusDto status() {
        ProviderIdentity identity;
        try {
            identity = cloudProvider.identity();
        } catch (RuntimeException ex) {
            identity = ProviderIdentity.unavailable(cloudProvider.providerName(),
                    cloudProperties.getRegion(),
                    Texts.redactSecrets(String.valueOf(ex.getMessage())));
        }

        return new SystemStatusDto(
                cloudProvider.providerName(),
                Texts.defaultIfBlank(identity.region(), cloudProperties.getRegion()),
                identity.credentialsAvailable(),
                identity.message(),
                identity.arn() == null ? identity.accountId() : identity.arn(),
                aiService.providerName(),
                aiProperties.getDefaultModel(),
                aiService.available(),
                monitoringProperties.isEnabled(),
                monitoringProperties.getPollIntervalMs(),
                remediationProperties.isExecutionEnabled(),
                remediationProperties.isForceDryRun(),
                remediationProperties.isRequireApproval());
    }

    /** Which model answers which pipeline stage — proves the multi-model routing is real. */
    public Map<String, String> aiRouting() {
        return aiService.routingTable();
    }

    public boolean multiModelConfigured() {
        return aiService.multiModelConfigured();
    }

    /**
     * Asks IAM whether the remediation actions would be permitted, without performing them.
     * Shown on the System page so a demo does not fail at approval time.
     */
    public Map<String, String> permissionPreflight() {
        return Map.of(
                "ec2:RebootInstances", describe(check("ec2:RebootInstances")),
                "ec2:StopInstances", describe(check("ec2:StopInstances")),
                "ec2:StartInstances", describe(check("ec2:StartInstances")),
                "ec2:CreateTags", describe(check("ec2:CreateTags")),
                "cloudwatch:GetMetricStatistics", describe(check("cloudwatch:GetMetricStatistics")));
    }

    private PermissionCheck check(String iamAction) {
        try {
            return cloudProvider.checkPermission(iamAction, "*");
        } catch (RuntimeException ex) {
            log.debug("Permission preflight for {} failed: {}", iamAction, ex.getMessage());
            return PermissionCheck.notEvaluated(
                    "Could not evaluate: " + Texts.redactSecrets(String.valueOf(ex.getMessage())));
        }
    }

    private String describe(PermissionCheck check) {
        if (!check.evaluated()) {
            return "not evaluated - " + Texts.defaultIfBlank(check.message(), "no simulator available");
        }
        return (check.allowed() ? "allowed" : "denied") + " - "
                + Texts.defaultIfBlank(check.message(), check.decision());
    }
}
