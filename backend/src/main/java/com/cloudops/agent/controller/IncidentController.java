package com.cloudops.agent.controller;

import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.cloudops.agent.dto.ActionExecutionDto;
import com.cloudops.agent.dto.AiAnalysisDto;
import com.cloudops.agent.dto.CreateIncidentRequest;
import com.cloudops.agent.dto.IncidentDetailDto;
import com.cloudops.agent.dto.IncidentDto;
import com.cloudops.agent.dto.PageResponse;
import com.cloudops.agent.dto.RecommendationDto;
import com.cloudops.agent.dto.ResolveIncidentRequest;
import com.cloudops.agent.entity.IncidentStatus;
import com.cloudops.agent.entity.Severity;
import com.cloudops.agent.service.ActionExecutionService;
import com.cloudops.agent.service.AiAnalysisService;
import com.cloudops.agent.service.IncidentService;
import com.cloudops.agent.service.RecommendationService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

/**
 * Incidents and everything hanging off one: AI analyses, recommendations and actions.
 *
 * {@code POST /{id}/analyze} is the manual entry point to the AI pipeline. It runs
 * synchronously so the caller sees the result, unlike the automatic path from the metric
 * poller, which runs it in the background.
 */
@RestController
@RequestMapping("/api/incidents")
@Tag(name = "Incidents", description = "Detected problems and their AI analysis")
public class IncidentController {

    private static final int MAX_PAGE_SIZE = 100;

    private final IncidentService incidentService;
    private final AiAnalysisService aiAnalysisService;
    private final RecommendationService recommendationService;
    private final ActionExecutionService actionExecutionService;

    public IncidentController(IncidentService incidentService,
                            AiAnalysisService aiAnalysisService,
                            RecommendationService recommendationService,
                            ActionExecutionService actionExecutionService) {
        this.incidentService = incidentService;
        this.aiAnalysisService = aiAnalysisService;
        this.recommendationService = recommendationService;
        this.actionExecutionService = actionExecutionService;
    }

    @GetMapping
    @Operation(summary = "Search incidents, oldest first (triage order)")
    public PageResponse<IncidentDto> search(
            @RequestParam(required = false) IncidentStatus status,
            @RequestParam(required = false) Severity severity,
            @RequestParam(required = false) Long resourceId,
            @RequestParam(defaultValue = "false") boolean activeOnly,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        // Oldest incident first, with id as a tiebreaker.
        //
        // Why not newest-first: this is the paginated triage list behind the Incidents screen.
        // The dashboard's "Recent incidents" panel is served separately by
        // findTop10ByOrderByOpenedAtDesc, so recency is already covered, and a triage queue
        // must not let the longest-burning incident sink as newer ones arrive above it.
        //
        // Why id is included: one poll can open several incidents for the same resource in the
        // same instant — a hot instance breaches CPUUtilization and NetworkIn in the same batch.
        // openedAt alone is therefore not a total order, and an unstable sort makes paging
        // unsound: the same row can appear on two pages, or be skipped entirely.
        //
        // Severity is deliberately not the sort key. It is persisted with @Enumerated(STRING)
        // in a VARCHAR(20), so ORDER BY severity is alphabetical and would rank MEDIUM above
        // CRITICAL. Ordering by real severity needs a persisted numeric rank, not a string sort.
        Pageable pageable = PageRequest.of(Math.max(page, 0), clampSize(size),
                Sort.by(Sort.Order.asc("openedAt"), Sort.Order.asc("id")));
        Page<IncidentDto> found =
                incidentService.searchDtos(status, severity, resourceId, activeOnly, pageable);
        return PageResponse.of(found, found.getContent());
    }

    @GetMapping("/{id}")
    @Operation(summary = "One incident with its metrics, analyses, recommendations and actions")
    public IncidentDetailDto detail(@PathVariable Long id) {
        return incidentService.detail(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('ADMIN','OPERATOR')")
    @Operation(summary = "Raise an incident by hand, for something the detector cannot see")
    public IncidentDto create(@Valid @RequestBody CreateIncidentRequest request) {
        return incidentService.createDto(request);
    }

    @PostMapping("/{id}/acknowledge")
    @PreAuthorize("hasAnyRole('ADMIN','OPERATOR')")
    @Operation(summary = "Take ownership of an incident")
    public IncidentDto acknowledge(@PathVariable Long id) {
        return incidentService.acknowledgeDto(id);
    }

    @PostMapping("/{id}/resolve")
    @PreAuthorize("hasAnyRole('ADMIN','OPERATOR')")
    public IncidentDto resolve(@PathVariable Long id,
                              @Valid @RequestBody(required = false) ResolveIncidentRequest request) {
        return incidentService.resolveDto(id, request == null ? null : request.note());
    }

    @PostMapping("/{id}/close")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Close an incident without claiming it was fixed")
    public IncidentDto close(@PathVariable Long id,
                            @Valid @RequestBody(required = false) ResolveIncidentRequest request) {
        return incidentService.closeDto(id, request == null ? null : request.note());
    }

    // ----- AI -----

    @GetMapping("/{id}/analyses")
    @Operation(summary = "Every AI call made about this incident, successful or not")
    public List<AiAnalysisDto> analyses(@PathVariable Long id) {
        return aiAnalysisService.listForIncident(id);
    }

    @PostMapping("/{id}/analyze")
    @PreAuthorize("hasAnyRole('ADMIN','OPERATOR')")
    @Operation(summary = "Run detection, diagnosis and recommendation now (synchronous)")
    public List<AiAnalysisDto> analyze(@PathVariable Long id) {
        return aiAnalysisService.analyze(id);
    }

    @PostMapping("/{id}/verify")
    @PreAuthorize("hasAnyRole('ADMIN','OPERATOR')")
    @Operation(summary = "Ask the model whether the remediation worked; metrics still decide")
    public AiAnalysisDto verify(@PathVariable Long id) {
        return aiAnalysisService.verifyRecovery(id);
    }

    // ----- related records -----

    @GetMapping("/{id}/recommendations")
    public List<RecommendationDto> recommendations(@PathVariable Long id) {
        return recommendationService.listForIncident(id);
    }

    @GetMapping("/{id}/actions")
    public List<ActionExecutionDto> actions(@PathVariable Long id) {
        return actionExecutionService.listForIncident(id);
    }

    private int clampSize(int size) {
        if (size <= 0) {
            return 20;
        }
        return Math.min(size, MAX_PAGE_SIZE);
    }
}
