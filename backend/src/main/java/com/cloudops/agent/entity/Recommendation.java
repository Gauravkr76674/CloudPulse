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
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

/**
 * A proposed remediation for an incident.
 *
 * {@code actionType} is a strongly typed member of the safe {@link ActionType} catalog.
 * {@code aiSuggestedAction} keeps the exact string the model produced, so that when a
 * model hallucinates something outside the catalog the row is still stored - with status
 * REJECTED_UNSAFE - as evidence that the guardrail worked.
 */
@Entity
@Table(name = "recommendations", indexes = {
        @Index(name = "idx_recommendation_incident", columnList = "incident_id"),
        @Index(name = "idx_recommendation_status", columnList = "status")
})
public class Recommendation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "incident_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_recommendation_incident"))
    private Incident incident;

    /** The RECOMMENDATION-stage analysis that produced this row (null for manual entries). */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "analysis_id",
            foreignKey = @ForeignKey(name = "fk_recommendation_analysis"))
    private AiAnalysis analysis;

    /** null only when the AI proposed something outside the catalog. */
    @Enumerated(EnumType.STRING)
    @Column(name = "action_type", length = 40)
    private ActionType actionType;

    @Column(name = "ai_suggested_action", length = 200)
    private String aiSuggestedAction;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(columnDefinition = "TEXT")
    private String rationale;

    @Column(name = "expected_impact", columnDefinition = "TEXT")
    private String expectedImpact;

    @Enumerated(EnumType.STRING)
    @Column(name = "risk_level", nullable = false, length = 20)
    private RiskLevel riskLevel = RiskLevel.MEDIUM;

    @Column
    private Double confidence;

    /** 1 = highest priority. Used to order the approval queue. */
    @Column(nullable = false)
    private int priority = 5;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private RecommendationStatus status = RecommendationStatus.PROPOSED;

    @Column(name = "requires_approval", nullable = false)
    private boolean requiresApproval = true;

    /** JSON blob of validated action parameters (never free-form commands). */
    @Column(name = "parameters_json", columnDefinition = "TEXT")
    private String parametersJson;

    @Column(name = "rejection_reason", length = 500)
    private String rejectionReason;

    @Column(name = "decided_by", length = 60)
    private String decidedBy;

    @Column(name = "decided_at")
    private Instant decidedAt;

    @Column(name = "decision_note", length = 500)
    private String decisionNote;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    public Recommendation() {
        // required by JPA
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

    /** True when an ADMIN may still act on this recommendation. */
    public boolean isActionable() {
        return status == RecommendationStatus.PROPOSED;
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

    public AiAnalysis getAnalysis() {
        return analysis;
    }

    public void setAnalysis(AiAnalysis analysis) {
        this.analysis = analysis;
    }

    public ActionType getActionType() {
        return actionType;
    }

    public void setActionType(ActionType actionType) {
        this.actionType = actionType;
    }

    public String getAiSuggestedAction() {
        return aiSuggestedAction;
    }

    public void setAiSuggestedAction(String aiSuggestedAction) {
        this.aiSuggestedAction = aiSuggestedAction;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getRationale() {
        return rationale;
    }

    public void setRationale(String rationale) {
        this.rationale = rationale;
    }

    public String getExpectedImpact() {
        return expectedImpact;
    }

    public void setExpectedImpact(String expectedImpact) {
        this.expectedImpact = expectedImpact;
    }

    public RiskLevel getRiskLevel() {
        return riskLevel;
    }

    public void setRiskLevel(RiskLevel riskLevel) {
        this.riskLevel = riskLevel;
    }

    public Double getConfidence() {
        return confidence;
    }

    public void setConfidence(Double confidence) {
        this.confidence = confidence;
    }

    public int getPriority() {
        return priority;
    }

    public void setPriority(int priority) {
        this.priority = priority;
    }

    public RecommendationStatus getStatus() {
        return status;
    }

    public void setStatus(RecommendationStatus status) {
        this.status = status;
    }

    public boolean isRequiresApproval() {
        return requiresApproval;
    }

    public void setRequiresApproval(boolean requiresApproval) {
        this.requiresApproval = requiresApproval;
    }

    public String getParametersJson() {
        return parametersJson;
    }

    public void setParametersJson(String parametersJson) {
        this.parametersJson = parametersJson;
    }

    public String getRejectionReason() {
        return rejectionReason;
    }

    public void setRejectionReason(String rejectionReason) {
        this.rejectionReason = rejectionReason;
    }

    public String getDecidedBy() {
        return decidedBy;
    }

    public void setDecidedBy(String decidedBy) {
        this.decidedBy = decidedBy;
    }

    public Instant getDecidedAt() {
        return decidedAt;
    }

    public void setDecidedAt(Instant decidedAt) {
        this.decidedAt = decidedAt;
    }

    public String getDecisionNote() {
        return decisionNote;
    }

    public void setDecisionNote(String decisionNote) {
        this.decisionNote = decisionNote;
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
