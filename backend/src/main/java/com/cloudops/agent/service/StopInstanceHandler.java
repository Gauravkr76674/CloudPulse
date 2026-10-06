package com.cloudops.agent.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.cloudops.agent.aws.CloudProvider;
import com.cloudops.agent.aws.model.ProviderActionResult;
import com.cloudops.agent.entity.ActionType;

/**
 * STOP_EC2_INSTANCE — one ec2:StopInstances call.
 *
 * The highest-risk action in the catalog: it takes a workload offline. It is in the whitelist
 * because a compromised or runaway instance sometimes must be stopped, but it is marked HIGH
 * risk and always requires an ADMIN approval.
 */
@Component
public class StopInstanceHandler implements ActionHandler {

    private static final Logger log = LoggerFactory.getLogger(StopInstanceHandler.class);

    private final CloudProvider cloudProvider;

    public StopInstanceHandler(CloudProvider cloudProvider) {
        this.cloudProvider = cloudProvider;
    }

    @Override
    public ActionType actionType() {
        return ActionType.STOP_EC2_INSTANCE;
    }

    @Override
    public ProviderActionResult execute(Target target) {
        log.warn("Stopping {} for incident {} - approved high-risk action",
                target.providerResourceId(), target.incidentId());
        return cloudProvider.stopInstance(target.providerResourceId());
    }
}
