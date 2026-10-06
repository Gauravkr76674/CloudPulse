package com.cloudops.agent.dto;

import java.util.List;

/** POST /api/resources/sync result. */
public record SyncResultDto(
        String provider,
        String region,
        int discovered,
        int created,
        int updated,
        int skipped,
        List<String> messages) {
}
