package com.cloudops.agent.controller;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.cloudops.agent.dto.SystemStatusDto;
import com.cloudops.agent.service.SystemService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * "Is this thing wired up?" for the System page: which cloud provider is active, whether
 * credentials resolved, which model answers which pipeline stage, and whether the remediation
 * switches are on.
 *
 * No secret is ever returned — only whether a key is present.
 */
@RestController
@RequestMapping("/api/system")
@Tag(name = "System", description = "Live configuration and connectivity status")
public class SystemController {

    private final SystemService systemService;

    public SystemController(SystemService systemService) {
        this.systemService = systemService;
    }

    @GetMapping("/status")
    @Operation(summary = "Provider, credentials, AI model and remediation switches")
    public SystemStatusDto status() {
        return systemService.status();
    }

    @GetMapping("/ai-routing")
    @Operation(summary = "Which model is used for each pipeline stage")
    public Map<String, Object> aiRouting() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("multiModelConfigured", systemService.multiModelConfigured());
        body.put("routing", systemService.aiRouting());
        return body;
    }

    @GetMapping("/permissions")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Ask IAM whether the remediation calls would be permitted")
    public Map<String, String> permissions() {
        return systemService.permissionPreflight();
    }
}
