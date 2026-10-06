package com.cloudops.agent.entity;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/**
 * One CloudWatch datapoint for one resource.
 *
 * The unique constraint on (resource, metric_name, statistic, metric_timestamp) makes
 * the poller idempotent: re-reading an overlapping CloudWatch window cannot create
 * duplicate rows.
 */
@Entity
@Table(name = "metrics",
        uniqueConstraints = @UniqueConstraint(name = "uk_metric_point",
                columnNames = {"resource_id", "metric_name", "statistic", "metric_timestamp"}),
        indexes = {
                @Index(name = "idx_metric_lookup", columnList = "resource_id,metric_name,metric_timestamp"),
                @Index(name = "idx_metric_anomalous", columnList = "anomalous")
        })
public class Metric {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "resource_id", nullable = false,
            foreignKey = @jakarta.persistence.ForeignKey(name = "fk_metric_resource"))
    private CloudResource resource;

    /** CloudWatch metric name, e.g. CPUUtilization. */
    @Column(name = "metric_name", nullable = false, length = 60)
    private String metricName;

    @Column(nullable = false, length = 40)
    private String namespace = "AWS/EC2";

    /** Average / Maximum / Sum - which CloudWatch statistic this row holds. */
    @Column(nullable = false, length = 20)
    private String statistic = "Average";

    /** "value" is a reserved word in MySQL, hence metric_value. */
    @Column(name = "metric_value", nullable = false)
    private Double value;

    @Column(length = 30)
    private String unit;

    /** Timestamp reported by CloudWatch for the datapoint. */
    @Column(name = "metric_timestamp", nullable = false)
    private Instant timestamp;

    @Column(name = "collected_at", nullable = false, updatable = false)
    private Instant collectedAt;

    /** Set by AnomalyDetectionService when this datapoint breached a rule. */
    @Column(nullable = false)
    private boolean anomalous = false;

    @Column(name = "threshold_value")
    private Double thresholdValue;

    @Column(name = "baseline_mean")
    private Double baselineMean;

    @Column(name = "baseline_std_dev")
    private Double baselineStdDev;

    @Column(name = "z_score")
    private Double zScore;

    public Metric() {
        // required by JPA
    }

    public Metric(CloudResource resource, String metricName, String statistic,
                  Double value, String unit, Instant timestamp) {
        this.resource = resource;
        this.metricName = metricName;
        this.statistic = statistic;
        this.value = value;
        this.unit = unit;
        this.timestamp = timestamp;
    }

    @PrePersist
    void onCreate() {
        this.collectedAt = Instant.now();
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

    public String getMetricName() {
        return metricName;
    }

    public void setMetricName(String metricName) {
        this.metricName = metricName;
    }

    public String getNamespace() {
        return namespace;
    }

    public void setNamespace(String namespace) {
        this.namespace = namespace;
    }

    public String getStatistic() {
        return statistic;
    }

    public void setStatistic(String statistic) {
        this.statistic = statistic;
    }

    public Double getValue() {
        return value;
    }

    public void setValue(Double value) {
        this.value = value;
    }

    public String getUnit() {
        return unit;
    }

    public void setUnit(String unit) {
        this.unit = unit;
    }

    public Instant getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(Instant timestamp) {
        this.timestamp = timestamp;
    }

    public Instant getCollectedAt() {
        return collectedAt;
    }

    public void setCollectedAt(Instant collectedAt) {
        this.collectedAt = collectedAt;
    }

    public boolean isAnomalous() {
        return anomalous;
    }

    public void setAnomalous(boolean anomalous) {
        this.anomalous = anomalous;
    }

    public Double getThresholdValue() {
        return thresholdValue;
    }

    public void setThresholdValue(Double thresholdValue) {
        this.thresholdValue = thresholdValue;
    }

    public Double getBaselineMean() {
        return baselineMean;
    }

    public void setBaselineMean(Double baselineMean) {
        this.baselineMean = baselineMean;
    }

    public Double getBaselineStdDev() {
        return baselineStdDev;
    }

    public void setBaselineStdDev(Double baselineStdDev) {
        this.baselineStdDev = baselineStdDev;
    }

    public Double getZScore() {
        return zScore;
    }

    public void setZScore(Double zScore) {
        this.zScore = zScore;
    }
}
