package com.cloudops.agent.entity;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/**
 * A cloud resource under management (an EC2 instance in the MVP).
 *
 * Per-resource threshold overrides live here so that one noisy instance can be tuned
 * without changing the global defaults in cloudops.detection.*.
 */
@Entity
@Table(name = "cloud_resources",
        uniqueConstraints = @UniqueConstraint(name = "uk_resource_id", columnNames = "resource_id"),
        indexes = {
                @Index(name = "idx_resource_monitored", columnList = "monitored"),
                @Index(name = "idx_resource_type", columnList = "type")
        })
public class CloudResource {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Provider identifier, e.g. the EC2 instance id i-0abc123. */
    @Column(name = "resource_id", nullable = false, length = 120)
    private String resourceId;

    @Column(nullable = false, length = 150)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private ResourceType type = ResourceType.EC2_INSTANCE;

    @Column(nullable = false, length = 30)
    private String region;

    @Column(name = "availability_zone", length = 40)
    private String availabilityZone;

    @Column(name = "instance_type", length = 40)
    private String instanceType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ResourceStatus status = ResourceStatus.UNKNOWN;

    /** When false the metric poller skips this resource entirely. */
    @Column(nullable = false)
    private boolean monitored = true;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ResourceSource source = ResourceSource.MANUAL;

    /** Provider tags serialised as JSON (MySQL has no JSONB, so this is TEXT). */
    @Column(name = "tags_json", columnDefinition = "TEXT")
    private String tagsJson;

    @Column(name = "private_ip", length = 45)
    private String privateIp;

    @Column(name = "public_ip", length = 45)
    private String publicIp;

    @Column(name = "launch_time")
    private Instant launchTime;

    /** null = fall back to cloudops.detection.default-cpu-threshold-percent */
    @Column(name = "cpu_threshold_percent")
    private Double cpuThresholdPercent;

    @Column(name = "network_in_threshold_bytes")
    private Double networkInThresholdBytes;

    /** null = fall back to cloudops.detection.consecutive-breaches-to-open */
    @Column(name = "consecutive_breaches_to_open")
    private Integer consecutiveBreachesToOpen;

    @Column(name = "last_checked_at")
    private Instant lastCheckedAt;

    @Column(name = "created_by", length = 60)
    private String createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    public CloudResource() {
        // required by JPA
    }

    public CloudResource(String resourceId, String name, ResourceType type, String region) {
        this.resourceId = resourceId;
        this.name = name;
        this.type = type;
        this.region = region;
    }

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getResourceId() {
        return resourceId;
    }

    public void setResourceId(String resourceId) {
        this.resourceId = resourceId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public ResourceType getType() {
        return type;
    }

    public void setType(ResourceType type) {
        this.type = type;
    }

    public String getRegion() {
        return region;
    }

    public void setRegion(String region) {
        this.region = region;
    }

    public String getAvailabilityZone() {
        return availabilityZone;
    }

    public void setAvailabilityZone(String availabilityZone) {
        this.availabilityZone = availabilityZone;
    }

    public String getInstanceType() {
        return instanceType;
    }

    public void setInstanceType(String instanceType) {
        this.instanceType = instanceType;
    }

    public ResourceStatus getStatus() {
        return status;
    }

    public void setStatus(ResourceStatus status) {
        this.status = status;
    }

    public boolean isMonitored() {
        return monitored;
    }

    public void setMonitored(boolean monitored) {
        this.monitored = monitored;
    }

    public ResourceSource getSource() {
        return source;
    }

    public void setSource(ResourceSource source) {
        this.source = source;
    }

    public String getTagsJson() {
        return tagsJson;
    }

    public void setTagsJson(String tagsJson) {
        this.tagsJson = tagsJson;
    }

    public String getPrivateIp() {
        return privateIp;
    }

    public void setPrivateIp(String privateIp) {
        this.privateIp = privateIp;
    }

    public String getPublicIp() {
        return publicIp;
    }

    public void setPublicIp(String publicIp) {
        this.publicIp = publicIp;
    }

    public Instant getLaunchTime() {
        return launchTime;
    }

    public void setLaunchTime(Instant launchTime) {
        this.launchTime = launchTime;
    }

    public Double getCpuThresholdPercent() {
        return cpuThresholdPercent;
    }

    public void setCpuThresholdPercent(Double cpuThresholdPercent) {
        this.cpuThresholdPercent = cpuThresholdPercent;
    }

    public Double getNetworkInThresholdBytes() {
        return networkInThresholdBytes;
    }

    public void setNetworkInThresholdBytes(Double networkInThresholdBytes) {
        this.networkInThresholdBytes = networkInThresholdBytes;
    }

    public Integer getConsecutiveBreachesToOpen() {
        return consecutiveBreachesToOpen;
    }

    public void setConsecutiveBreachesToOpen(Integer consecutiveBreachesToOpen) {
        this.consecutiveBreachesToOpen = consecutiveBreachesToOpen;
    }

    public Instant getLastCheckedAt() {
        return lastCheckedAt;
    }

    public void setLastCheckedAt(Instant lastCheckedAt) {
        this.lastCheckedAt = lastCheckedAt;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(String createdBy) {
        this.createdBy = createdBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
