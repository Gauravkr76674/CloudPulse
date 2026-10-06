package com.cloudops.agent.entity;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

/**
 * The record of one attempt to execute a safe action.
 *
 * A row is written BEFORE anything touches AWS (status PENDING/RUNNING) and updated
 * afterwards, so a crash mid-execution still leaves a trace. BLOCKED rows are the
 * ones a guardrail stopped.
 */
@Entity
@Table(name = "action_executions", indexes = {
        @Index(name = "idx_action_incident", columnList = "incident_id"),
        @Index(name = "idx_action_resource_time", columnList = "resource_id,created_at"),
        @Index(name = "idx_action_status", columnList = "status")
})
public class ActionExecution {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "incident_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_action_incident"))
    private Incident incident;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "recommendation_id",
            foreignKey = @ForeignKey(name = "fk_action_recommendation"))
    private Recommendation recommendation;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "resource_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_action_resource"))
    private CloudResource resource;

    @Enumerated(EnumType.STRING)
    @Column(name = "action_type", nullable = false, length = 40)
    private ActionType actionType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ActionStatus status = ActionStatus.PENDING;

    /** true = validate and audit, but never call AWS. */
    @Column(name = "dry_run", nullable = false)
    private boolean dryRun = false;

    @Column(name = "requested_by", length = 60)
    private String requestedBy;

    @Column(name = "approved_by", length = 60)
    private String approvedBy;

    @Column(name = "parameters_json", columnDefinition = "TEXT")
    private String parametersJson;

    @Column(name = "result_message", columnDefinition = "TEXT")
    private String resultMessage;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    /** Why a guardrail refused to run this action. */
    @Column(name = "blocked_reason", length = 500)
    private String blockedReason;

    /** AWS request id, so the execution can be traced in CloudTrail. */
    @Column(name = "provider_request_id", length = 100)
    private String providerRequestId;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Column(name = "duration_ms")
    private Long durationMs;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public ActionExecution() {
        // required by JPA
    }

    @PrePersist
    void onCreate() {
        this.createdAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Incident getIncident() {
        return incident;
    }

    public void setIncident(Incident incident) {
        this.incident = incident;
    }

    public Recommendation getRecommendation() {
        return recommendation;
    }

    public void setRecommendation(Recommendation recommendation) {
        this.recommendation = recommendation;
    }

    public CloudResource getResource() {
        return resource;
    }

    public void setResource(CloudResource resource) {
        this.resource = resource;
    }

    public ActionType getActionType() {
        return actionType;
    }

    public void setActionType(ActionType actionType) {
        this.actionType = actionType;
    }

    public ActionStatus getStatus() {
        return status;
    }

    public void setStatus(ActionStatus status) {
        this.status = status;
    }

    public boolean isDryRun() {
        return dryRun;
    }

    public void setDryRun(boolean dryRun) {
        this.dryRun = dryRun;
    }

    public String getRequestedBy() {
        return requestedBy;
    }

    public void setRequestedBy(String requestedBy) {
        this.requestedBy = requestedBy;
    }

    public String getApprovedBy() {
        return approvedBy;
    }

    public void setApprovedBy(String approvedBy) {
        this.approvedBy = approvedBy;
    }

    public String getParametersJson() {
        return parametersJson;
    }

    public void setParametersJson(String parametersJson) {
        this.parametersJson = parametersJson;
    }

    public String getResultMessage() {
        return resultMessage;
    }

    public void setResultMessage(String resultMessage) {
        this.resultMessage = resultMessage;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public String getBlockedReason() {
        return blockedReason;
    }

    public void setBlockedReason(String blockedReason) {
        this.blockedReason = blockedReason;
    }

    public String getProviderRequestId() {
        return providerRequestId;
    }

    public void setProviderRequestId(String providerRequestId) {
        this.providerRequestId = providerRequestId;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(Instant startedAt) {
        this.startedAt = startedAt;
    }

    public Instant getFinishedAt() {
        return finishedAt;
    }

    public void setFinishedAt(Instant finishedAt) {
        this.finishedAt = finishedAt;
    }

    public Long getDurationMs() {
        return durationMs;
    }

    public void setDurationMs(Long durationMs) {
        this.durationMs = durationMs;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
