package com.cloudops.agent.controller;

import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.cloudops.agent.dto.ActionCatalogEntryDto;
import com.cloudops.agent.dto.ActionExecutionDto;
import com.cloudops.agent.dto.PageResponse;
import com.cloudops.agent.entity.ActionStatus;
import com.cloudops.agent.service.ActionExecutionService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * Remediation history and the safe-action catalog.
 *
 * Read-only: there is deliberately no endpoint here that starts an action. The only way to
 * execute anything is to approve a recommendation, which is what makes the approval gate
 * impossible to bypass.
 */
@RestController
@RequestMapping("/api/actions")
@Tag(name = "Actions", description = "Remediation history and the safe action whitelist")
public class ActionController {

    private static final int MAX_PAGE_SIZE = 100;

    private final ActionExecutionService actionExecutionService;

    public ActionController(ActionExecutionService actionExecutionService) {
        this.actionExecutionService = actionExecutionService;
    }

    @GetMapping
    @Operation(summary = "Every action attempted, newest first, optionally filtered by status")
    public PageResponse<ActionExecutionDto> history(
            @RequestParam(required = false) ActionStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        Pageable pageable = PageRequest.of(Math.max(page, 0), size <= 0 ? 20 : Math.min(size, MAX_PAGE_SIZE));
        Page<ActionExecutionDto> found = actionExecutionService.history(status, pageable);
        return PageResponse.of(found, found.getContent());
    }

    @GetMapping("/catalog")
    @Operation(summary = "The complete list of actions this agent is ever allowed to perform")
    public List<ActionCatalogEntryDto> catalog() {
        return actionExecutionService.catalog();
    }

    @GetMapping("/{id}")
    public ActionExecutionDto get(@PathVariable Long id) {
        return actionExecutionService.getDto(id);
    }
}
