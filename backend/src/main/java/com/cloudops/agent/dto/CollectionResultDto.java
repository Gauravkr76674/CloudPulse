package com.cloudops.agent.dto;

import java.time.Instant;
import java.util.List;

/** Outcome of one metric-collection pass (scheduled or triggered from the UI). */
public record CollectionResultDto(
        String provider,
        int resourcesPolled,
        int datapointsStored,
        int anomaliesDetected,
        int incidentsOpened,
        int incidentsUpdated,
        Instant startedAt,
        Instant finishedAt,
        long durationMs,
        List<String> errors) {
}
