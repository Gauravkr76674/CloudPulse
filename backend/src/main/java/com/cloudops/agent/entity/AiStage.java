package com.cloudops.agent.entity;

/**
 * The specialised responsibilities of the AI layer. Each stage has its own prompt
 * and may be routed to its own model (see cloudops.ai.models in application.yml).
 *
 * DETECTION      - classify/enrich the anomaly signal and set severity
 * DIAGNOSIS      - explain the probable root cause from metrics + resource context
 * RECOMMENDATION - propose one or more actions from the safe action catalog
 * VERIFICATION   - after remediation, judge whether the incident actually recovered
 */
public enum AiStage {
    DETECTION,
    DIAGNOSIS,
    RECOMMENDATION,
    VERIFICATION
}
