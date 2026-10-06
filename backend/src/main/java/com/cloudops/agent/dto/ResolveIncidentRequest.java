package com.cloudops.agent.dto;

import jakarta.validation.constraints.Size;

/** POST /api/incidents/{id}/resolve body. */
public record ResolveIncidentRequest(@Size(max = 2000) String note) {
}
