package com.cloudops.agent.config;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Binds <code>cloudops.monitoring.*</code> - how often and how far back we poll CloudWatch. */
@ConfigurationProperties(prefix = "cloudops.monitoring")
public class MonitoringProperties {

    private boolean enabled = true;
    private long pollIntervalMs = 300_000L;
    private long initialDelayMs = 20_000L;
    private int cloudwatchPeriodSeconds = 300;
    private int lookbackMinutes = 30;
    private List<String> metricNames = new ArrayList<>(
            Arrays.asList("CPUUtilization", "NetworkIn", "NetworkOut", "StatusCheckFailed"));
    private int retentionDays = 14;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public long getPollIntervalMs() {
        return pollIntervalMs;
    }

    public void setPollIntervalMs(long pollIntervalMs) {
        this.pollIntervalMs = pollIntervalMs;
    }

    public long getInitialDelayMs() {
        return initialDelayMs;
    }

    public void setInitialDelayMs(long initialDelayMs) {
        this.initialDelayMs = initialDelayMs;
    }

    public int getCloudwatchPeriodSeconds() {
        return cloudwatchPeriodSeconds;
    }

    public void setCloudwatchPeriodSeconds(int cloudwatchPeriodSeconds) {
        this.cloudwatchPeriodSeconds = cloudwatchPeriodSeconds;
    }

    public int getLookbackMinutes() {
        return lookbackMinutes;
    }

    public void setLookbackMinutes(int lookbackMinutes) {
        this.lookbackMinutes = lookbackMinutes;
    }

    public List<String> getMetricNames() {
        return metricNames;
    }

    public void setMetricNames(List<String> metricNames) {
        this.metricNames = metricNames;
    }

    public int getRetentionDays() {
        return retentionDays;
    }

    public void setRetentionDays(int retentionDays) {
        this.retentionDays = retentionDays;
    }
}
