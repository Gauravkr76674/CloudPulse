package com.cloudops.agent.service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.cloudops.agent.dto.DashboardSummaryDto;
import com.cloudops.agent.dto.DashboardSummaryDto.ActionStats;
import com.cloudops.agent.dto.DashboardSummaryDto.AiStats;
import com.cloudops.agent.dto.DashboardSummaryDto.IncidentStats;
import com.cloudops.agent.dto.DashboardSummaryDto.ResourceStats;
import com.cloudops.agent.entity.ActionStatus;
import com.cloudops.agent.entity.IncidentStatus;
import com.cloudops.agent.entity.RecommendationStatus;
import com.cloudops.agent.entity.ResourceStatus;
import com.cloudops.agent.entity.Severity;
import com.cloudops.agent.repository.ActionExecutionRepository;
import com.cloudops.agent.repository.AiAnalysisRepository;
import com.cloudops.agent.repository.CloudResourceRepository;
import com.cloudops.agent.repository.IncidentRepository;
import com.cloudops.agent.repository.RecommendationRepository;
import com.cloudops.agent.util.DtoMapper;

/**
 * One query per tile for the landing page. Deliberately a pile of counts rather than one
 * clever join: each number is independently readable, and MySQL answers indexed counts fast
 * enough that clarity is worth more than a saved round trip at this scale.
 */
@Service
public class DashboardService {

    private final CloudResourceRepository resourceRepository;
    private final IncidentRepository incidentRepository;
    private final AiAnalysisRepository analysisRepository;
    private final RecommendationRepository recommendationRepository;
    private final ActionExecutionRepository actionRepository;
    private final RecommendationService recommendationService;
    private final SystemService systemService;

    public DashboardService(CloudResourceRepository resourceRepository,
                          IncidentRepository incidentRepository,
                          AiAnalysisRepository analysisRepository,
                          RecommendationRepository recommendationRepository,
                          ActionExecutionRepository actionRepository,
                          RecommendationService recommendationService,
                          SystemService systemService) {
        this.resourceRepository = resourceRepository;
        this.incidentRepository = incidentRepository;
        this.analysisRepository = analysisRepository;
        this.recommendationRepository = recommendationRepository;
        this.actionRepository = actionRepository;
        this.recommendationService = recommendationService;
        this.systemService = systemService;
    }

    @Transactional(readOnly = true)
    public DashboardSummaryDto summary() {
        Instant dayAgo = Instant.now().minus(24, ChronoUnit.HOURS);

        ResourceStats resources = new ResourceStats(
                resourceRepository.count(),
                resourceRepository.countByMonitoredTrue(),
                resourceRepository.countByStatus(ResourceStatus.RUNNING),
                resourceRepository.countByStatus(ResourceStatus.STOPPED));

        IncidentStats incidents = new IncidentStats(
                incidentRepository.countByStatusIn(IncidentService.ACTIVE_STATUSES),
                incidentRepository.countByStatus(IncidentStatus.OPEN),
                incidentRepository.countByStatus(IncidentStatus.ANALYZING),
                incidentRepository.countByStatus(IncidentStatus.AWAITING_APPROVAL),
                incidentRepository.countByStatus(IncidentStatus.REMEDIATING),
                incidentRepository.countByStatus(IncidentStatus.RESOLVED),
                incidentRepository.countByOpenedAtAfter(dayAgo),
                incidentRepository.countByResolvedAtAfter(dayAgo),
                incidentRepository.averageResolutionSeconds(dayAgo));

        AiStats ai = new AiStats(
                analysisRepository.count(),
                analysisRepository.countBySuccessfulFalse(),
                recommendationRepository.countByStatus(RecommendationStatus.PROPOSED),
                recommendationRepository.countByStatus(RecommendationStatus.REJECTED_UNSAFE));

        ActionStats actions = new ActionStats(
                actionRepository.countByStatus(ActionStatus.PENDING),
                actionRepository.countByStatus(ActionStatus.SUCCEEDED),
                actionRepository.countByStatus(ActionStatus.FAILED),
                actionRepository.countByStatus(ActionStatus.BLOCKED));

        Map<String, Long> bySeverity = new LinkedHashMap<>();
        for (Severity severity : Severity.values()) {
            bySeverity.put(severity.name(),
                    incidentRepository.countBySeverityAndStatusIn(
                            severity, IncidentService.ACTIVE_STATUSES));
        }

        return new DashboardSummaryDto(
                resources,
                incidents,
                ai,
                actions,
                bySeverity,
                DtoMapper.toIncidentDtos(incidentRepository.findTop10ByOrderByOpenedAtDesc()),
                DtoMapper.toActionDtos(actionRepository.findTop10ByOrderByCreatedAtDesc()),
                recommendationService.pending(),
                systemService.status());
    }

    /** Severity breakdown on its own, for the chart's refresh call. */
    @Transactional(readOnly = true)
    public Map<String, Long> activeIncidentsBySeverity() {
        Map<String, Long> bySeverity = new LinkedHashMap<>();
        for (Severity severity : Severity.values()) {
            bySeverity.put(severity.name(),
                    incidentRepository.countBySeverityAndStatusIn(
                            severity, IncidentService.ACTIVE_STATUSES));
        }
        return bySeverity;
    }
}
