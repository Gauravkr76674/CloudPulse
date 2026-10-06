package com.cloudops.agent.service;

import org.springframework.stereotype.Component;

import com.cloudops.agent.aws.model.ProviderActionResult;
import com.cloudops.agent.dto.CollectionResultDto;
import com.cloudops.agent.entity.ActionType;

/**
 * COLLECT_MORE_METRICS — re-polls CloudWatch for this resource right now instead of waiting
 * for the next scheduled sweep. Read-only against AWS: it changes nothing in the account.
 *
 * Useful as the first response to a borderline signal, and it is the action that lets the
 * agent gather evidence without touching anything.
 */
@Component
public class CollectMoreMetricsHandler implements ActionHandler {

    private final MetricCollectionService metricCollectionService;

    public CollectMoreMetricsHandler(MetricCollectionService metricCollectionService) {
        this.metricCollectionService = metricCollectionService;
    }

    @Override
    public ActionType actionType() {
        return ActionType.COLLECT_MORE_METRICS;
    }

    @Override
    public ProviderActionResult execute(Target target) {
        CollectionResultDto result = metricCollectionService.collectResource(target.resourceId());
        if (!result.errors().isEmpty()) {
            return ProviderActionResult.failed("Re-poll failed: " + String.join("; ", result.errors()));
        }
        return ProviderActionResult.ok(String.format(
                "Re-polled %s: %d new datapoint(s), %d anomaly(ies).",
                target.providerResourceId(), result.datapointsStored(), result.anomaliesDetected()),
                null);
    }
}
