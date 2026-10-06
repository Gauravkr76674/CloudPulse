package com.cloudops.agent.ai;

/**
 * Raw result of a model call, before any JSON parsing.
 *
 * A failed call is represented as a value (not an exception) so the pipeline can record
 * the failure on the incident and carry on — an unreachable AI must never stop metric
 * collection or incident tracking.
 */
public record AiResponse(
        boolean success,
        String content,
        String provider,
        String model,
        long latencyMs,
        Integer promptTokens,
        Integer completionTokens,
        String errorMessage) {

    public static AiResponse ok(String content, String provider, String model, long latencyMs,
            Integer promptTokens, Integer completionTokens) {
        return new AiResponse(true, content, provider, model, latencyMs, promptTokens,
                completionTokens, null);
    }

    public static AiResponse failed(String provider, String model, long latencyMs, String error) {
        return new AiResponse(false, null, provider, model, latencyMs, null, null, error);
    }
}
