package com.cloudops.agent.ai.model;

import java.util.Optional;

import com.cloudops.agent.ai.AiResponse;

/**
 * A parsed stage result paired with the transport metadata (model, latency, tokens)
 * that the service layer persists on {@link com.cloudops.agent.entity.AiAnalysis}.
 *
 * {@code payload} is empty when the call failed or the response could not be parsed;
 * {@code raw} always carries the details for the audit trail.
 */
public record AiStageOutcome<T>(T payload, AiResponse raw, String parseError) {

    public static <T> AiStageOutcome<T> of(T payload, AiResponse raw) {
        return new AiStageOutcome<>(payload, raw, null);
    }

    public static <T> AiStageOutcome<T> unparsable(AiResponse raw, String parseError) {
        return new AiStageOutcome<>(null, raw, parseError);
    }

    public static <T> AiStageOutcome<T> transportFailure(AiResponse raw) {
        return new AiStageOutcome<>(null, raw, null);
    }

    public boolean successful() {
        return payload != null;
    }

    public Optional<T> payloadOptional() {
        return Optional.ofNullable(payload);
    }

    /** Human-readable failure reason for the AiAnalysis row, or null when successful. */
    public String failureReason() {
        if (successful()) {
            return null;
        }
        if (parseError != null) {
            return parseError;
        }
        return raw == null ? "no response" : raw.errorMessage();
    }
}
