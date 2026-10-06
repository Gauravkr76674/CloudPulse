package com.cloudops.agent.util;

import java.util.List;
import java.util.stream.Collectors;

import com.cloudops.agent.dto.ActionCatalogEntryDto;
import com.cloudops.agent.dto.ActionExecutionDto;
import com.cloudops.agent.dto.AiAnalysisDto;
import com.cloudops.agent.dto.AuditLogDto;
import com.cloudops.agent.dto.CloudResourceDto;
import com.cloudops.agent.dto.IncidentDto;
import com.cloudops.agent.dto.MetricDto;
import com.cloudops.agent.dto.NotificationDto;
import com.cloudops.agent.dto.RecommendationDto;
import com.cloudops.agent.dto.UserDto;
import com.cloudops.agent.entity.ActionExecution;
import com.cloudops.agent.entity.ActionType;
import com.cloudops.agent.entity.AiAnalysis;
import com.cloudops.agent.entity.AuditLog;
import com.cloudops.agent.entity.CloudResource;
import com.cloudops.agent.entity.Incident;
import com.cloudops.agent.entity.Metric;
import com.cloudops.agent.entity.Notification;
import com.cloudops.agent.entity.Recommendation;
import com.cloudops.agent.entity.RecommendationStatus;
import com.cloudops.agent.entity.User;

/**
 * Entity -> DTO conversion. Kept as one static class on purpose: it has no state and
 * no dependencies, which keeps the service layer easy to unit test.
 *
 * Note: the methods that read lazy collections (toIncidentDto) must be called inside
 * a transaction.
 */
public final class DtoMapper {

    private DtoMapper() {
    }

    public static UserDto toUserDto(User user) {
        if (user == null) {
            return null;
        }
        return new UserDto(user.getId(), user.getUsername(), user.getEmail(), user.getFullName(),
                user.getRole(), user.isEnabled(), user.getCreatedAt(), user.getLastLoginAt());
    }

    public static List<UserDto> toUserDtos(List<User> users) {
        return users.stream().map(DtoMapper::toUserDto).collect(Collectors.toList());
    }

    public static CloudResourceDto toResourceDto(CloudResource resource, long activeIncidents) {
        if (resource == null) {
            return null;
        }
        return new CloudResourceDto(
                resource.getId(),
                resource.getResourceId(),
                resource.getName(),
                resource.getType(),
                resource.getRegion(),
                resource.getAvailabilityZone(),
                resource.getInstanceType(),
                resource.getStatus(),
                resource.isMonitored(),
                resource.getSource(),
                resource.getTagsJson(),
                resource.getPrivateIp(),
                resource.getPublicIp(),
                resource.getLaunchTime(),
                resource.getCpuThresholdPercent(),
                resource.getNetworkInThresholdBytes(),
                resource.getConsecutiveBreachesToOpen(),
                resource.getLastCheckedAt(),
                resource.getCreatedBy(),
                resource.getCreatedAt(),
                activeIncidents);
    }

    public static MetricDto toMetricDto(Metric metric) {
        if (metric == null) {
            return null;
        }
        CloudResource resource = metric.getResource();
        return new MetricDto(
                metric.getId(),
                resource == null ? null : resource.getId(),
                resource == null ? null : resource.getName(),
                metric.getMetricName(),
                metric.getStatistic(),
                metric.getNamespace(),
                metric.getValue(),
                metric.getUnit(),
                metric.getTimestamp(),
                metric.isAnomalous(),
                metric.getThresholdValue(),
                metric.getBaselineMean(),
                metric.getZScore());
    }

    public static List<MetricDto> toMetricDtos(List<Metric> metrics) {
        return metrics.stream().map(DtoMapper::toMetricDto).collect(Collectors.toList());
    }

    /** Must run inside a transaction: reads the incident's lazy child collections. */
    public static IncidentDto toIncidentDto(Incident incident) {
        if (incident == null) {
            return null;
        }
        int pending = (int) incident.getRecommendations().stream()
                .filter(r -> r.getStatus() == RecommendationStatus.PROPOSED)
                .count();
        CloudResource resource = incident.getResource();
        return new IncidentDto(
                incident.getId(),
                resource == null ? null : resource.getId(),
                resource == null ? null : resource.getName(),
                resource == null ? null : resource.getResourceId(),
                incident.getTitle(),
                incident.getStatus(),
                incident.getSeverity(),
                incident.getMetricName(),
                incident.getRuleName(),
                incident.getTriggerValue(),
                incident.getThresholdValue(),
                incident.getOccurrenceCount(),
                incident.getOpenedAt(),
                incident.getLastSeenAt(),
                incident.getAcknowledgedBy(),
                incident.getAcknowledgedAt(),
                incident.getResolvedBy(),
                incident.getResolvedAt(),
                incident.getAnalyses().size(),
                incident.getRecommendations().size(),
                pending,
                incident.getActions().size());
    }

    public static List<IncidentDto> toIncidentDtos(List<Incident> incidents) {
        return incidents.stream().map(DtoMapper::toIncidentDto).collect(Collectors.toList());
    }

    public static AiAnalysisDto toAnalysisDto(AiAnalysis analysis) {
        if (analysis == null) {
            return null;
        }
        return new AiAnalysisDto(
                analysis.getId(),
                analysis.getIncident() == null ? null : analysis.getIncident().getId(),
                analysis.getStage(),
                analysis.getProvider(),
                analysis.getModelName(),
                analysis.getSummary(),
                analysis.getRootCause(),
                analysis.getReasoning(),
                analysis.getConfidence(),
                analysis.getSuggestedSeverity(),
                analysis.getLatencyMs(),
                analysis.getPromptTokens(),
                analysis.getCompletionTokens(),
                analysis.isSuccessful(),
                analysis.getErrorMessage(),
                analysis.getRawResponse(),
                analysis.getCreatedAt());
    }

    public static List<AiAnalysisDto> toAnalysisDtos(List<AiAnalysis> analyses) {
        return analyses.stream().map(DtoMapper::toAnalysisDto).collect(Collectors.toList());
    }

    public static RecommendationDto toRecommendationDto(Recommendation recommendation) {
        if (recommendation == null) {
            return null;
        }
        ActionType actionType = recommendation.getActionType();
        return new RecommendationDto(
                recommendation.getId(),
                recommendation.getIncident() == null ? null : recommendation.getIncident().getId(),
                actionType,
                actionType == null ? null : actionType.getDisplayName(),
                recommendation.getAiSuggestedAction(),
                recommendation.getTitle(),
                recommendation.getRationale(),
                recommendation.getExpectedImpact(),
                recommendation.getRiskLevel(),
                recommendation.getConfidence(),
                recommendation.getPriority(),
                recommendation.getStatus(),
                recommendation.isRequiresApproval(),
                actionType != null && actionType.isMutating(),
                recommendation.getRejectionReason(),
                recommendation.getDecidedBy(),
                recommendation.getDecidedAt(),
                recommendation.getDecisionNote(),
                recommendation.getCreatedAt());
    }

    public static List<RecommendationDto> toRecommendationDtos(List<Recommendation> recommendations) {
        return recommendations.stream().map(DtoMapper::toRecommendationDto).collect(Collectors.toList());
    }

    public static ActionExecutionDto toActionDto(ActionExecution action) {
        if (action == null) {
            return null;
        }
        CloudResource resource = action.getResource();
        return new ActionExecutionDto(
                action.getId(),
                action.getIncident() == null ? null : action.getIncident().getId(),
                action.getRecommendation() == null ? null : action.getRecommendation().getId(),
                resource == null ? null : resource.getId(),
                resource == null ? null : resource.getName(),
                resource == null ? null : resource.getResourceId(),
                action.getActionType(),
                action.getActionType() == null ? null : action.getActionType().getDisplayName(),
                action.getStatus(),
                action.isDryRun(),
                action.getRequestedBy(),
                action.getApprovedBy(),
                action.getResultMessage(),
                action.getErrorMessage(),
                action.getBlockedReason(),
                action.getProviderRequestId(),
                action.getStartedAt(),
                action.getFinishedAt(),
                action.getDurationMs(),
                action.getCreatedAt());
    }

    public static List<ActionExecutionDto> toActionDtos(List<ActionExecution> actions) {
        return actions.stream().map(DtoMapper::toActionDto).collect(Collectors.toList());
    }

    public static NotificationDto toNotificationDto(Notification notification) {
        if (notification == null) {
            return null;
        }
        return new NotificationDto(
                notification.getId(),
                notification.getType(),
                notification.getSeverity(),
                notification.getTitle(),
                notification.getMessage(),
                notification.getIncident() == null ? null : notification.getIncident().getId(),
                notification.isRead(),
                notification.getCreatedAt());
    }

    public static List<NotificationDto> toNotificationDtos(List<Notification> notifications) {
        return notifications.stream().map(DtoMapper::toNotificationDto).collect(Collectors.toList());
    }

    public static AuditLogDto toAuditDto(AuditLog auditLog) {
        if (auditLog == null) {
            return null;
        }
        return new AuditLogDto(
                auditLog.getId(),
                auditLog.getUsername(),
                auditLog.getRole(),
                auditLog.getAction(),
                auditLog.getTargetType(),
                auditLog.getTargetId(),
                auditLog.getOutcome(),
                auditLog.getDetails(),
                auditLog.getIpAddress(),
                auditLog.getCreatedAt());
    }

    public static List<AuditLogDto> toAuditDtos(List<AuditLog> logs) {
        return logs.stream().map(DtoMapper::toAuditDto).collect(Collectors.toList());
    }

    public static ActionCatalogEntryDto toCatalogEntry(ActionType type, boolean executable, String note) {
        return new ActionCatalogEntryDto(
                type,
                type.getDisplayName(),
                type.getDescription(),
                type.isMutating(),
                type.isApprovalRequired(),
                type.getDefaultRisk(),
                executable,
                note);
    }
}
