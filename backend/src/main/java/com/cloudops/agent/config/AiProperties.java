package com.cloudops.agent.config;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Binds <code>cloudops.ai.*</code>.
 *
 * The {@link #models} map is what makes the agent "multi-model": each pipeline
 * stage (DETECTION / DIAGNOSIS / RECOMMENDATION / VERIFICATION) may be routed to a
 * different model id. Blank entries fall back to {@link #defaultModel}.
 */
@ConfigurationProperties(prefix = "cloudops.ai")
public class AiProperties {

    /** "stub" (offline deterministic analyser) or "http" (OpenAI-compatible API). */
    private String provider = "stub";
    private String baseUrl = "https://api.openai.com/v1";
    private String apiKey = "";
    private String defaultModel = "gpt-4o-mini";
    private int timeoutSeconds = 45;
    private int maxRetries = 2;
    private double temperature = 0.1;
    private int maxOutputTokens = 900;
    private boolean autoAnalyzeNewIncidents = true;
    private Map<String, String> models = new LinkedHashMap<>();

    public boolean isHttpProvider() {
        return "http".equalsIgnoreCase(provider);
    }

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String getDefaultModel() {
        return defaultModel;
    }

    public void setDefaultModel(String defaultModel) {
        this.defaultModel = defaultModel;
    }

    public int getTimeoutSeconds() {
        return timeoutSeconds;
    }

    public void setTimeoutSeconds(int timeoutSeconds) {
        this.timeoutSeconds = timeoutSeconds;
    }

    public int getMaxRetries() {
        return maxRetries;
    }

    public void setMaxRetries(int maxRetries) {
        this.maxRetries = maxRetries;
    }

    public double getTemperature() {
        return temperature;
    }

    public void setTemperature(double temperature) {
        this.temperature = temperature;
    }

    public int getMaxOutputTokens() {
        return maxOutputTokens;
    }

    public void setMaxOutputTokens(int maxOutputTokens) {
        this.maxOutputTokens = maxOutputTokens;
    }

    public boolean isAutoAnalyzeNewIncidents() {
        return autoAnalyzeNewIncidents;
    }

    public void setAutoAnalyzeNewIncidents(boolean autoAnalyzeNewIncidents) {
        this.autoAnalyzeNewIncidents = autoAnalyzeNewIncidents;
    }

    public Map<String, String> getModels() {
        return models;
    }

    public void setModels(Map<String, String> models) {
        this.models = models;
    }
}
