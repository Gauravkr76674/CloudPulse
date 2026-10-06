package com.cloudops.agent.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.cloudops.agent.aws.CloudProvider;
import com.cloudops.agent.aws.model.DiscoveredResource;
import com.cloudops.agent.config.CloudProperties;
import com.cloudops.agent.dto.CloudResourceDto;
import com.cloudops.agent.dto.CreateResourceRequest;
import com.cloudops.agent.dto.SyncResultDto;
import com.cloudops.agent.dto.UpdateResourceRequest;
import com.cloudops.agent.entity.CloudResource;
import com.cloudops.agent.entity.ResourceSource;
import com.cloudops.agent.entity.ResourceStatus;
import com.cloudops.agent.entity.ResourceType;
import com.cloudops.agent.exception.ConflictException;
import com.cloudops.agent.exception.NotFoundException;
import com.cloudops.agent.repository.CloudResourceRepository;
import com.cloudops.agent.repository.IncidentRepository;
import com.cloudops.agent.security.CurrentUser;
import com.cloudops.agent.util.DtoMapper;
import com.cloudops.agent.util.JsonUtil;
import com.cloudops.agent.util.Texts;

/**
 * The inventory of things being watched.
 *
 * Resources arrive two ways: discovered from the provider ({@link #sync()}) or registered
 * by hand. Sync is written to be safely repeatable — it matches on {@code resourceId} and
 * refreshes only the provider-owned fields, so a re-sync never overwrites the thresholds
 * an operator tuned or silently re-enables monitoring they switched off.
 */
@Service
public class CloudResourceService {

    private static final Logger log = LoggerFactory.getLogger(CloudResourceService.class);

    private final CloudResourceRepository resourceRepository;
    private final IncidentRepository incidentRepository;
    private final CloudProvider cloudProvider;
    private final CloudProperties cloudProperties;
    private final AuditService auditService;
    private final JsonUtil jsonUtil;

    public CloudResourceService(CloudResourceRepository resourceRepository,
                               IncidentRepository incidentRepository,
                               CloudProvider cloudProvider,
                               CloudProperties cloudProperties,
                               AuditService auditService,
                               JsonUtil jsonUtil) {
        this.resourceRepository = resourceRepository;
        this.incidentRepository = incidentRepository;
        this.cloudProvider = cloudProvider;
        this.cloudProperties = cloudProperties;
        this.auditService = auditService;
        this.jsonUtil = jsonUtil;
    }

    // ---------------------------------------------------------------------
    // reads
    // ---------------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<CloudResourceDto> list() {
        List<CloudResource> resources = resourceRepository.findAllByOrderByNameAsc();
        List<CloudResourceDto> dtos = new ArrayList<>(resources.size());
        for (CloudResource resource : resources) {
            dtos.add(toDto(resource));
        }
        return dtos;
    }

    @Transactional(readOnly = true)
    public List<CloudResourceDto> search(String term) {
        if (!Texts.hasText(term)) {
            return list();
        }
        List<CloudResourceDto> dtos = new ArrayList<>();
        for (CloudResource resource : resourceRepository.search(term.trim().toLowerCase())) {
            dtos.add(toDto(resource));
        }
        return dtos;
    }

    @Transactional(readOnly = true)
    public CloudResourceDto getDto(Long id) {
        return toDto(require(id));
    }

    @Transactional(readOnly = true)
    public CloudResource get(Long id) {
        return require(id);
    }

    /** The working set for the metric poller. */
    @Transactional(readOnly = true)
    public List<CloudResource> monitoredResources() {
        return resourceRepository.findByMonitoredTrue();
    }

    private CloudResourceDto toDto(CloudResource resource) {
        long active = incidentRepository.countByResourceAndStatusIn(
                resource, IncidentService.ACTIVE_STATUSES);
        return DtoMapper.toResourceDto(resource, active);
    }

    // ---------------------------------------------------------------------
    // manual registration and edits
    // ---------------------------------------------------------------------

    @Transactional
    public CloudResourceDto create(CreateResourceRequest request) {
        String resourceId = request.resourceId().trim();
        if (resourceRepository.existsByResourceId(resourceId)) {
            throw new ConflictException("Resource " + resourceId + " is already registered.");
        }

        CloudResource resource = new CloudResource();
        resource.setResourceId(resourceId);
        resource.setName(request.name().trim());
        resource.setType(request.type() == null ? ResourceType.EC2_INSTANCE : request.type());
        resource.setRegion(Texts.defaultIfBlank(request.region(), cloudProperties.getRegion()));
        resource.setInstanceType(request.instanceType());
        resource.setStatus(ResourceStatus.UNKNOWN);
        resource.setMonitored(request.monitored() == null || request.monitored());
        resource.setSource(ResourceSource.MANUAL);
        resource.setCpuThresholdPercent(request.cpuThresholdPercent());
        resource.setNetworkInThresholdBytes(request.networkInThresholdBytes());
        resource.setConsecutiveBreachesToOpen(request.consecutiveBreachesToOpen());
        resource.setCreatedBy(CurrentUser.username());

        CloudResource saved = resourceRepository.save(resource);
        auditService.success("RESOURCE_CREATED", "CloudResource", saved.getId(),
                "Registered " + resourceId + " manually");

        // Ask the provider what it actually looks like; harmless if it does not know it
        refreshFromProvider(saved);
        return toDto(saved);
    }

    @Transactional
    public CloudResourceDto update(Long id, UpdateResourceRequest request) {
        CloudResource resource = require(id);
        StringBuilder changes = new StringBuilder();

        if (Texts.hasText(request.name()) && !request.name().equals(resource.getName())) {
            changes.append("name ").append(resource.getName()).append(" -> ").append(request.name()).append("; ");
            resource.setName(request.name().trim());
        }
        if (request.monitored() != null && request.monitored() != resource.isMonitored()) {
            changes.append("monitored ").append(resource.isMonitored())
                    .append(" -> ").append(request.monitored()).append("; ");
            resource.setMonitored(request.monitored());
        }
        if (!Objects.equals(request.cpuThresholdPercent(), resource.getCpuThresholdPercent())) {
            changes.append("cpuThreshold ").append(Texts.number(resource.getCpuThresholdPercent()))
                    .append(" -> ").append(Texts.number(request.cpuThresholdPercent())).append("; ");
            resource.setCpuThresholdPercent(request.cpuThresholdPercent());
        }
        if (!Objects.equals(request.networkInThresholdBytes(), resource.getNetworkInThresholdBytes())) {
            changes.append("networkInThreshold ").append(Texts.number(resource.getNetworkInThresholdBytes()))
                    .append(" -> ").append(Texts.number(request.networkInThresholdBytes())).append("; ");
            resource.setNetworkInThresholdBytes(request.networkInThresholdBytes());
        }
        if (!Objects.equals(request.consecutiveBreachesToOpen(), resource.getConsecutiveBreachesToOpen())) {
            changes.append("consecutiveBreaches ").append(resource.getConsecutiveBreachesToOpen())
                    .append(" -> ").append(request.consecutiveBreachesToOpen()).append("; ");
            resource.setConsecutiveBreachesToOpen(request.consecutiveBreachesToOpen());
        }

        CloudResource saved = resourceRepository.save(resource);
        auditService.success("RESOURCE_UPDATED", "CloudResource", id,
                changes.isEmpty() ? "No effective change" : changes.toString());
        return toDto(saved);
    }

    @Transactional
    public CloudResourceDto setMonitored(Long id, boolean monitored) {
        CloudResource resource = require(id);
        resource.setMonitored(monitored);
        CloudResource saved = resourceRepository.save(resource);
        auditService.success(monitored ? "RESOURCE_MONITORING_ON" : "RESOURCE_MONITORING_OFF",
                "CloudResource", id, resource.getResourceId());
        return toDto(saved);
    }

    /**
     * Removes a resource and everything recorded about it. Incidents are deleted explicitly
     * first because they are the parent of analyses, recommendations and actions.
     */
    @Transactional
    public void delete(Long id) {
        CloudResource resource = require(id);
        String resourceId = resource.getResourceId();
        incidentRepository.deleteByResource(resource);
        resourceRepository.delete(resource);
        auditService.success("RESOURCE_DELETED", "CloudResource", id, resourceId);
        log.info("Deleted resource {} and its incident history", resourceId);
    }

    // ---------------------------------------------------------------------
    // provider synchronisation
    // ---------------------------------------------------------------------

    /**
     * Pulls the instance list from the provider and reconciles it with the database.
     *
     * Provider-owned fields (status, IPs, instance type, AZ, tags) are refreshed every time;
     * operator-owned fields (name, monitored, thresholds) are only set when the row is new.
     */
    @Transactional
    public SyncResultDto sync() {
        List<String> messages = new ArrayList<>();
        List<DiscoveredResource> discovered;
        try {
            discovered = cloudProvider.discoverInstances();
        } catch (RuntimeException ex) {
            String message = "Discovery failed: " + Texts.redactSecrets(String.valueOf(ex.getMessage()));
            log.warn(message);
            auditService.failure("RESOURCE_SYNC", "CloudResource", null, message);
            return new SyncResultDto(cloudProvider.providerName(), cloudProperties.getRegion(),
                    0, 0, 0, 0, List.of(message));
        }

        int created = 0;
        int updated = 0;
        int skipped = 0;

        for (DiscoveredResource found : discovered) {
            Optional<CloudResource> existing = resourceRepository.findByResourceId(found.resourceId());
            if (existing.isPresent()) {
                CloudResource resource = existing.get();
                if (applyProviderFields(resource, found)) {
                    resourceRepository.save(resource);
                    updated++;
                } else {
                    skipped++;
                }
                continue;
            }

            CloudResource resource = new CloudResource();
            resource.setResourceId(found.resourceId());
            resource.setName(Texts.defaultIfBlank(found.name(), found.resourceId()));
            resource.setType(found.type() == null ? ResourceType.EC2_INSTANCE : found.type());
            resource.setMonitored(true);
            resource.setSource(cloudProvider.real() ? ResourceSource.AWS_DISCOVERY : ResourceSource.MOCK);
            resource.setCreatedBy(CurrentUser.username());
            applyProviderFields(resource, found);
            resourceRepository.save(resource);
            created++;
            messages.add("Discovered " + found.resourceId() + " (" + found.status() + ")");
        }

        auditService.success("RESOURCE_SYNC", "CloudResource", null,
                String.format("provider=%s discovered=%d created=%d updated=%d unchanged=%d",
                        cloudProvider.providerName(), discovered.size(), created, updated, skipped));
        log.info("Sync from {}: {} discovered, {} created, {} updated",
                cloudProvider.providerName(), discovered.size(), created, updated);

        return new SyncResultDto(cloudProvider.providerName(), cloudProperties.getRegion(),
                discovered.size(), created, updated, skipped, messages);
    }

    /** Re-reads one instance from the provider; used after registration and after a reboot. */
    @Transactional
    public CloudResourceDto refresh(Long id) {
        CloudResource resource = require(id);
        refreshFromProvider(resource);
        return toDto(resource);
    }

    private void refreshFromProvider(CloudResource resource) {
        try {
            Optional<DiscoveredResource> found = cloudProvider.describeInstance(resource.getResourceId());
            if (found.isEmpty()) {
                log.debug("Provider {} does not know {}", cloudProvider.providerName(),
                        resource.getResourceId());
                return;
            }
            if (applyProviderFields(resource, found.get())) {
                resourceRepository.save(resource);
            }
        } catch (RuntimeException ex) {
            // A stale status is far better than a failed request
            log.warn("Could not refresh {}: {}", resource.getResourceId(),
                    Texts.redactSecrets(String.valueOf(ex.getMessage())));
        }
    }

    /**
     * Copies provider-owned fields onto the entity.
     *
     * @return true when anything actually changed
     */
    private boolean applyProviderFields(CloudResource resource, DiscoveredResource found) {
        boolean changed = false;

        if (found.status() != null && found.status() != resource.getStatus()) {
            resource.setStatus(found.status());
            changed = true;
        }
        if (!Objects.equals(found.region(), resource.getRegion())) {
            resource.setRegion(Texts.defaultIfBlank(found.region(), cloudProperties.getRegion()));
            changed = true;
        }
        if (!Objects.equals(found.availabilityZone(), resource.getAvailabilityZone())) {
            resource.setAvailabilityZone(found.availabilityZone());
            changed = true;
        }
        if (!Objects.equals(found.instanceType(), resource.getInstanceType())) {
            resource.setInstanceType(found.instanceType());
            changed = true;
        }
        if (!Objects.equals(found.privateIp(), resource.getPrivateIp())) {
            resource.setPrivateIp(found.privateIp());
            changed = true;
        }
        if (!Objects.equals(found.publicIp(), resource.getPublicIp())) {
            resource.setPublicIp(found.publicIp());
            changed = true;
        }
        if (found.launchTime() != null && !found.launchTime().equals(resource.getLaunchTime())) {
            resource.setLaunchTime(found.launchTime());
            changed = true;
        }

        String tagsJson = found.tags() == null || found.tags().isEmpty()
                ? resource.getTagsJson()
                : jsonUtil.toJson(found.tags());
        if (!Objects.equals(tagsJson, resource.getTagsJson())) {
            resource.setTagsJson(Texts.truncate(tagsJson, 2000));
            changed = true;
        }
        return changed;
    }

    /** Stamped after every poll so the UI can show how fresh the data is. */
    @Transactional
    public void markChecked(Long resourceId) {
        resourceRepository.findById(resourceId).ifPresent(resource -> {
            resource.setLastCheckedAt(Instant.now());
            resourceRepository.save(resource);
        });
    }

    private CloudResource require(Long id) {
        return resourceRepository.findById(id)
                .orElseThrow(() -> NotFoundException.of("CloudResource", id));
    }
}
