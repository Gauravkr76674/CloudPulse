package com.cloudops.agent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Binds <code>cloudops.detection.*</code>.
 *
 * Two detection signals are configured here:
 *  1. a hard threshold (e.g. CPU &gt; 90%) that must be breached N times in a row;
 *  2. a rolling statistical baseline (z-score) that catches abnormal spikes
 *     which are still below the hard threshold.
 */
@ConfigurationProperties(prefix = "cloudops.detection")
public class DetectionProperties {

    private double defaultCpuThresholdPercent = 90.0;
    private double defaultNetworkInThresholdBytes = 50_000_000.0;
    private int consecutiveBreachesToOpen = 2;
    private boolean baselineEnabled = true;
    private int baselineWindowSize = 20;
    private int baselineMinSamples = 8;
    private double baselineZThreshold = 3.0;

    public double getDefaultCpuThresholdPercent() {
        return defaultCpuThresholdPercent;
    }

    public void setDefaultCpuThresholdPercent(double defaultCpuThresholdPercent) {
        this.defaultCpuThresholdPercent = defaultCpuThresholdPercent;
    }

    public double getDefaultNetworkInThresholdBytes() {
        return defaultNetworkInThresholdBytes;
    }

    public void setDefaultNetworkInThresholdBytes(double defaultNetworkInThresholdBytes) {
        this.defaultNetworkInThresholdBytes = defaultNetworkInThresholdBytes;
    }

    public int getConsecutiveBreachesToOpen() {
        return consecutiveBreachesToOpen;
    }

    public void setConsecutiveBreachesToOpen(int consecutiveBreachesToOpen) {
        this.consecutiveBreachesToOpen = consecutiveBreachesToOpen;
    }

    public boolean isBaselineEnabled() {
        return baselineEnabled;
    }

    public void setBaselineEnabled(boolean baselineEnabled) {
        this.baselineEnabled = baselineEnabled;
    }

    public int getBaselineWindowSize() {
        return baselineWindowSize;
    }

    public void setBaselineWindowSize(int baselineWindowSize) {
        this.baselineWindowSize = baselineWindowSize;
    }

    public int getBaselineMinSamples() {
        return baselineMinSamples;
    }

    public void setBaselineMinSamples(int baselineMinSamples) {
        this.baselineMinSamples = baselineMinSamples;
    }

    public double getBaselineZThreshold() {
        return baselineZThreshold;
    }

    public void setBaselineZThreshold(double baselineZThreshold) {
        this.baselineZThreshold = baselineZThreshold;
    }
}
