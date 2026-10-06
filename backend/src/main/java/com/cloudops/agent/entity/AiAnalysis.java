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
 * One AI pipeline stage result for an incident. A single incident typically has a
 * DIAGNOSIS row and a RECOMMENDATION row, each possibly from a different model -
 * which is what makes the "multi-model" behaviour visible on the dashboard.
 *
 * The raw model payload is kept as TEXT (MySQL has no JSONB) for the viva demo and
 * for debugging bad responses.
 */
@Entity
@Table(name = "ai_analyses", indexes = {
        @Index(name = "idx_analysis_incident", columnList = "incident_id"),
        @Index(name = "idx_analysis_stage", columnList = "stage")
})
public class AiAnalysis {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "incident_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_analysis_incident"))
    private Incident incident;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AiStage stage;

    /** "stub" or "http". */
    @Column(name = "provider", nullable = false, length = 20)
    private String provider;

    @Column(name = "model_name", nullable = false, length = 80)
    private String modelName;

    @Column(name = "summary", length = 500)
    private String summary;

    @Column(name = "root_cause", columnDefinition = "TEXT")
    private String rootCause;

    @Column(name = "reasoning", columnDefinition = "TEXT")
    private String reasoning;

    /** 0.0 - 1.0 as reported by the model. */
    @Column(name = "confidence")
    private Double confidence;

    @Enumerated(EnumType.STRING)
    @Column(name = "suggested_severity", length = 20)
    private Severity suggestedSeverity;

    @Column(name = "raw_response", columnDefinition = "TEXT")
    private String rawResponse;

    @Column(name = "prompt_tokens")
    private Integer promptTokens;

    @Column(name = "completion_tokens")
    private Integer completionTokens;

    @Column(name = "latency_ms")
    private Long latencyMs;

    /** false when the call failed or the response could not be parsed. */
    @Column(nullable = false)
    private boolean successful = true;

    @Column(name = "error_message", length = 500)
    private String errorMessage;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public AiAnalysis() {
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

    public AiStage getStage() {
        return stage;
    }

    public void setStage(AiStage stage) {
        this.stage = stage;
    }

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    public String getModelName() {
        return modelName;
    }

    public void setModelName(String modelName) {
        this.modelName = modelName;
    }

    public String getSummary() {
        return summary;
    }

    public void setSummary(String summary) {
        this.summary = summary;
    }

    public String getRootCause() {
        return rootCause;
    }

    public void setRootCause(String rootCause) {
        this.rootCause = rootCause;
    }

    public String getReasoning() {
        return reasoning;
    }

    public void setReasoning(String reasoning) {
        this.reasoning = reasoning;
    }

    public Double getConfidence() {
        return confidence;
    }

    public void setConfidence(Double confidence) {
        this.confidence = confidence;
    }

    public Severity getSuggestedSeverity() {
        return suggestedSeverity;
    }

    public void setSuggestedSeverity(Severity suggestedSeverity) {
        this.suggestedSeverity = suggestedSeverity;
    }

    public String getRawResponse() {
        return rawResponse;
    }

    public void setRawResponse(String rawResponse) {
        this.rawResponse = rawResponse;
    }

    public Integer getPromptTokens() {
        return promptTokens;
    }

    public void setPromptTokens(Integer promptTokens) {
        this.promptTokens = promptTokens;
    }

    public Integer getCompletionTokens() {
        return completionTokens;
    }

    public void setCompletionTokens(Integer completionTokens) {
        this.completionTokens = completionTokens;
    }

    public Long getLatencyMs() {
        return latencyMs;
    }

    public void setLatencyMs(Long latencyMs) {
        this.latencyMs = latencyMs;
    }

    public boolean isSuccessful() {
        return successful;
    }

    public void setSuccessful(boolean successful) {
        this.successful = successful;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
