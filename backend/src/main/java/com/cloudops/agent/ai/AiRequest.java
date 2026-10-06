package com.cloudops.agent.ai;

import com.cloudops.agent.entity.AiStage;

/**
 * One call to a model. Built by {@link AiService}; consumed by an {@link AiClient}.
 *
 * The stage is carried along so the router can pick a stage-specific model and so the
 * persisted {@link com.cloudops.agent.entity.AiAnalysis} row records which
 * responsibility produced it.
 */
public record AiRequest(
        AiStage stage,
        String model,
        String systemPrompt,
        String userPrompt,
        double temperature,
        int maxOutputTokens) {
}
