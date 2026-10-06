package com.cloudops.agent.controller;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.cloudops.agent.dto.CloudResourceDto;
import com.cloudops.agent.dto.CreateResourceRequest;
import com.cloudops.agent.dto.SyncResultDto;
import com.cloudops.agent.dto.UpdateResourceRequest;
import com.cloudops.agent.service.CloudResourceService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

/**
 * The monitored inventory.
 *
 * Reading is open to any authenticated user (VIEWER included); changing what is monitored or
 * what the thresholds are needs OPERATOR, and deleting a resource — which deletes its incident
 * history with it — needs ADMIN.
 */
@RestController
@RequestMapping("/api/resources")
@Tag(name = "Cloud resources", description = "Inventory of monitored AWS resources")
public class CloudResourceController {

    private final CloudResourceService resourceService;

    public CloudResourceController(CloudResourceService resourceService) {
        this.resourceService = resourceService;
    }

    @GetMapping
    @Operation(summary = "List resources, optionally filtered by name, id, tag or type")
    public List<CloudResourceDto> list(@RequestParam(required = false) String search) {
        return search == null || search.isBlank()
                ? resourceService.list()
                : resourceService.search(search);
    }

    @GetMapping("/{id}")
    public CloudResourceDto get(@PathVariable Long id) {
        return resourceService.getDto(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('ADMIN','OPERATOR')")
    @Operation(summary = "Register a resource by its AWS id and pull its current state")
    public CloudResourceDto create(@Valid @RequestBody CreateResourceRequest request) {
        return resourceService.create(request);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','OPERATOR')")
    @Operation(summary = "Update operator-owned fields: name, monitoring flag and thresholds")
    public CloudResourceDto update(@PathVariable Long id,
                                  @Valid @RequestBody UpdateResourceRequest request) {
        return resourceService.update(id, request);
    }

    @PatchMapping("/{id}/monitored")
    @PreAuthorize("hasAnyRole('ADMIN','OPERATOR')")
    @Operation(summary = "Start or stop polling this resource")
    public CloudResourceDto setMonitored(@PathVariable Long id, @RequestParam boolean monitored) {
        return resourceService.setMonitored(id, monitored);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Remove a resource and its incident history")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        resourceService.delete(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/sync")
    @PreAuthorize("hasAnyRole('ADMIN','OPERATOR')")
    @Operation(summary = "Discover instances from the provider and reconcile the inventory")
    public SyncResultDto sync() {
        return resourceService.sync();
    }

    @PostMapping("/{id}/refresh")
    @PreAuthorize("hasAnyRole('ADMIN','OPERATOR')")
    @Operation(summary = "Re-read one resource's live state from the provider")
    public CloudResourceDto refresh(@PathVariable Long id) {
        return resourceService.refresh(id);
    }
}
