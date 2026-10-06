package com.cloudops.agent.entity;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.hibernate.annotations.BatchSize;

import jakarta.persistence.CascadeType;
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
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/**
 * An incident opened by the anomaly detector.
 *
 * The {@code dedupKey} (resource + metric + rule) is unique among ACTIVE incidents,
 * which is how repeated breaches of the same rule roll into one incident with an
 * increasing {@code occurrenceCount} instead of flooding the dashboard.
 */
@Entity
@Table(name = "incidents",
        uniqueConstraints = @UniqueConstraint(name = "uk_incident_active_dedup",
                columnNames = {"dedup_key", "active_flag"}),
        indexes = {
                @Index(name = "idx_incident_status", columnList = "status"),
                @Index(name = "idx_incident_resource", columnList = "resource_id"),
                @Index(name = "idx_incident_opened", columnList = "opened_at")
        })
public class Incident {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "resource_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_incident_resource"))
    private CloudResource resource;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private IncidentStatus status = IncidentStatus.OPEN;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Severity severity = Severity.MEDIUM;

    /** Which metric triggered this, e.g. CPUUtilization. */
    @Column(name = "metric_name", nullable = false, length = 60)
    private String metricName;

    /** Rule that fired: THRESHOLD_BREACH or BASELINE_DEVIATION. */
    @Column(name = "rule_name", nullable = false, length = 40)
    private String ruleName;

    @Column(name = "trigger_value")
    private Double triggerValue;

    @Column(name = "threshold_value")
    private Double thresholdValue;

    @Column(name = "dedup_key", nullable = false, length = 200)
    private String dedupKey;

    /**
     * Trick to keep the "one active incident per dedup key" rule enforceable in MySQL:
     * 1 while the incident is active, and set to the incident id once it closes, so the
     * (dedup_key, active_flag) unique constraint only ever collides on live incidents.
     */
    @Column(name = "active_flag", nullable = false)
    private Long activeFlag = 1L;

    @Column(name = "occurrence_count", nullable = false)
    private int occurrenceCount = 1;

    @Column(name = "opened_at", nullable = false, updatable = false)
    private Instant openedAt;

    @Column(name = "last_seen_at")
    private Instant lastSeenAt;

    @Column(name = "acknowledged_at")
    private Instant acknowledgedAt;

    @Column(name = "acknowledged_by", length = 60)
    private String acknowledgedBy;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @Column(name = "resolved_by", length = 60)
    private String resolvedBy;

    @Column(name = "resolution_note", columnDefinition = "TEXT")
    private String resolutionNote;

    @Column(name = "updated_at")
    private Instant updatedAt;

    // @BatchSize keeps listing a page of incidents to a couple of extra selects per
    // collection instead of one per incident (these are read for the DTO counts).
    @OneToMany(mappedBy = "incident", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @OrderBy("createdAt ASC")
    @BatchSize(size = 50)
    private List<AiAnalysis> analyses = new ArrayList<>();

    @OneToMany(mappedBy = "incident", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @OrderBy("createdAt ASC")
    @BatchSize(size = 50)
    private List<Recommendation> recommendations = new ArrayList<>();

    @OneToMany(mappedBy = "incident", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @OrderBy("createdAt ASC")
    @BatchSize(size = 50)
    private List<ActionExecution> actions = new ArrayList<>();

    public Incident() {
        // required by JPA
    }

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        this.openedAt = now;
        this.updatedAt = now;
        if (this.lastSeenAt == null) {
            this.lastSeenAt = now;
        }
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }

    /** Convenience helper used by the services to keep both sides of the link in sync. */
    public void addAnalysis(AiAnalysis analysis) {
        analysis.setIncident(this);
        this.analyses.add(analysis);
    }

    public void addRecommendation(Recommendation recommendation) {
        recommendation.setIncident(this);
        this.recommendations.add(recommendation);
    }

    public void addAction(ActionExecution action) {
        action.setIncident(this);
        this.actions.add(action);
    }

    /** Releases the active dedup slot so a future breach can open a fresh incident. */
    public void releaseActiveSlot() {
        this.activeFlag = this.id == null ? 0L : this.id;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public CloudResource getResource() {
        return resource;
    }

    public void setResource(CloudResource resource) {
        this.resource = resource;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public IncidentStatus getStatus() {
        return status;
    }

    public void setStatus(IncidentStatus status) {
        this.status = status;
    }

    public Severity getSeverity() {
        return severity;
    }

    public void setSeverity(Severity severity) {
        this.severity = severity;
    }

    public String getMetricName() {
        return metricName;
    }

    public void setMetricName(String metricName) {
        this.metricName = metricName;
    }

    public String getRuleName() {
        return ruleName;
    }

    public void setRuleName(String ruleName) {
        this.ruleName = ruleName;
    }

    public Double getTriggerValue() {
        return triggerValue;
    }

    public void setTriggerValue(Double triggerValue) {
        this.triggerValue = triggerValue;
    }

    public Double getThresholdValue() {
        return thresholdValue;
    }

    public void setThresholdValue(Double thresholdValue) {
        this.thresholdValue = thresholdValue;
    }

    public String getDedupKey() {
        return dedupKey;
    }

    public void setDedupKey(String dedupKey) {
        this.dedupKey = dedupKey;
    }

    public Long getActiveFlag() {
        return activeFlag;
    }

    public void setActiveFlag(Long activeFlag) {
        this.activeFlag = activeFlag;
    }

    public int getOccurrenceCount() {
        return occurrenceCount;
    }

    public void setOccurrenceCount(int occurrenceCount) {
        this.occurrenceCount = occurrenceCount;
    }

    public Instant getOpenedAt() {
        return openedAt;
    }

    public void setOpenedAt(Instant openedAt) {
        this.openedAt = openedAt;
    }

    public Instant getLastSeenAt() {
        return lastSeenAt;
    }

    public void setLastSeenAt(Instant lastSeenAt) {
        this.lastSeenAt = lastSeenAt;
    }

    public Instant getAcknowledgedAt() {
        return acknowledgedAt;
    }

    public void setAcknowledgedAt(Instant acknowledgedAt) {
        this.acknowledgedAt = acknowledgedAt;
    }

    public String getAcknowledgedBy() {
        return acknowledgedBy;
    }

    public void setAcknowledgedBy(String acknowledgedBy) {
        this.acknowledgedBy = acknowledgedBy;
    }

    public Instant getResolvedAt() {
        return resolvedAt;
    }

    public void setResolvedAt(Instant resolvedAt) {
        this.resolvedAt = resolvedAt;
    }

    public String getResolvedBy() {
        return resolvedBy;
    }

    public void setResolvedBy(String resolvedBy) {
        this.resolvedBy = resolvedBy;
    }

    public String getResolutionNote() {
        return resolutionNote;
    }

    public void setResolutionNote(String resolutionNote) {
        this.resolutionNote = resolutionNote;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    public List<AiAnalysis> getAnalyses() {
        return analyses;
    }

    public void setAnalyses(List<AiAnalysis> analyses) {
        this.analyses = analyses;
    }

    public List<Recommendation> getRecommendations() {
        return recommendations;
    }

    public void setRecommendations(List<Recommendation> recommendations) {
        this.recommendations = recommendations;
    }

    public List<ActionExecution> getActions() {
        return actions;
    }

    public void setActions(List<ActionExecution> actions) {
        this.actions = actions;
    }
}
