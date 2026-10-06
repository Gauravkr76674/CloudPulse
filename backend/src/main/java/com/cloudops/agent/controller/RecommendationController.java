package com.cloudops.agent.controller;

import java.util.List;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.cloudops.agent.dto.ActionExecutionDto;
import com.cloudops.agent.dto.ApproveRecommendationRequest;
import com.cloudops.agent.dto.RecommendationDto;
import com.cloudops.agent.dto.RejectRecommendationRequest;
import com.cloudops.agent.service.ActionExecutionService;
import com.cloudops.agent.service.RecommendationService;
import com.cloudops.agent.util.DtoMapper;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

/**
 * The approval queue — the human gate in the whole design.
 *
 * {@code POST /{id}/approve} is ADMIN-only and is the only route from an AI suggestion to a
 * real AWS call. It returns the resulting action row, which may equally be BLOCKED: a guardrail
 * refusing is a normal outcome, not an error.
 *
 * Send {@code {"dryRun": true}} to walk the entire path — validation, approval, audit — while
 * making no provider call at all.
 */
@RestController
@RequestMapping("/api/recommendations")
@Tag(name = "Recommendations", description = "AI proposals and the human approval gate")
public class RecommendationController {

    private final RecommendationService recommendationService;
    private final ActionExecutionService actionExecutionService;

    public RecommendationController(RecommendationService recommendationService,
                                  ActionExecutionService actionExecutionService) {
        this.recommendationService = recommendationService;
        this.actionExecutionService = actionExecutionService;
    }

    @GetMapping("/pending")
    @Operation(summary = "Everything waiting on a human decision")
    public List<RecommendationDto> pending() {
        return recommendationService.pending();
    }

    @GetMapping("/blocked")
    @Operation(summary = "Suggestions the safety layer refused, kept as evidence")
    public List<RecommendationDto> blocked() {
        return recommendationService.blockedByGuardrails();
    }

    @GetMapping("/{id}")
    public RecommendationDto get(@PathVariable Long id) {
        return DtoMapper.toRecommendationDto(recommendationService.get(id));
    }

    @PostMapping("/{id}/approve")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Approve and execute one predefined safe action (ADMIN only)")
    public ActionExecutionDto approve(@PathVariable Long id,
                                     @Valid @RequestBody(required = false)
                                     ApproveRecommendationRequest request) {
        return actionExecutionService.approveAndExecute(id, request);
    }

    @PostMapping("/{id}/reject")
    @PreAuthorize("hasAnyRole('ADMIN','OPERATOR')")
    @Operation(summary = "Refuse a recommendation; nothing is executed")
    public RecommendationDto reject(@PathVariable Long id,
                                   @Valid @RequestBody(required = false)
                                   RejectRecommendationRequest request) {
        return DtoMapper.toRecommendationDto(
                recommendationService.reject(id, request == null ? null : request.note()));
    }
}
