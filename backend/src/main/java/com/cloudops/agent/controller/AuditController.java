package com.cloudops.agent.controller;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.cloudops.agent.dto.AuditLogDto;
import com.cloudops.agent.dto.PageResponse;
import com.cloudops.agent.entity.AuditLog;
import com.cloudops.agent.service.AuditService;
import com.cloudops.agent.util.DtoMapper;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * The append-only audit trail: who approved what, which guardrail refused what, and every
 * action the agent took. ADMIN-only, because it is the record used to review the agent's
 * behaviour and nothing in it can be edited or deleted through the API.
 */
@RestController
@RequestMapping("/api/audit")
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Audit", description = "Append-only record of every decision and action")
public class AuditController {

    private static final int MAX_PAGE_SIZE = 200;

    private final AuditService auditService;

    public AuditController(AuditService auditService) {
        this.auditService = auditService;
    }

    @GetMapping
    @Operation(summary = "Audit entries, newest first")
    public PageResponse<AuditLogDto> recent(@RequestParam(defaultValue = "0") int page,
                                           @RequestParam(defaultValue = "50") int size) {
        Pageable pageable = PageRequest.of(Math.max(page, 0), size <= 0 ? 50 : Math.min(size, MAX_PAGE_SIZE));
        Page<AuditLog> found = auditService.recent(pageable);
        return PageResponse.of(found, DtoMapper.toAuditDtos(found.getContent()));
    }
}
