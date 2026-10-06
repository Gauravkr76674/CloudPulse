package com.cloudops.agent.ai;

import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.cloudops.agent.config.AiProperties;
import com.cloudops.agent.entity.AiStage;

import jakarta.annotation.PostConstruct;

/**
 * Decides which model serves which stage — the "multi-model" part of the agent.
 *
 * Configure per-stage overrides with AI_MODEL_DETECTION, AI_MODEL_DIAGNOSIS,
 * AI_MODEL_RECOMMENDATION and AI_MODEL_VERIFICATION; anything left blank falls back to
 * AI_MODEL. A cheap fast model can therefore triage while a stronger one diagnoses,
 * without changing a line of pipeline code.
 */
@Component
public class AiModelRouter {

    private static final Logger log = LoggerFactory.getLogger(AiModelRouter.class);

    private final AiProperties aiProperties;

    public AiModelRouter(AiProperties aiProperties) {
        this.aiProperties = aiProperties;
    }

    @PostConstruct
    void logRouting() {
        log.info("AI model routing: {}", routingTable());
    }

    public String modelFor(AiStage stage) {
        Map<String, String> models = aiProperties.getModels();
        if (models != null) {
            String configured = models.get(stage.name());
            if (configured != null && !configured.isBlank()) {
                return configured.trim();
            }
        }
        return aiProperties.getDefaultModel();
    }

    /** Stage → model, for the System page and startup logging. */
    public Map<String, String> routingTable() {
        Map<String, String> table = new LinkedHashMap<>();
        for (AiStage stage : AiStage.values()) {
            table.put(stage.name(), modelFor(stage));
        }
        return table;
    }

    /** True when at least one stage is pinned to a non-default model. */
    public boolean multiModelConfigured() {
        String defaultModel = aiProperties.getDefaultModel();
        return routingTable().values().stream().anyMatch(model -> !model.equals(defaultModel));
    }
}
