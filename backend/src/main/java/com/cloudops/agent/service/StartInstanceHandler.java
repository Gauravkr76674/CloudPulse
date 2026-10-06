package com.cloudops.agent.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.cloudops.agent.aws.CloudProvider;
import com.cloudops.agent.aws.model.ProviderActionResult;
import com.cloudops.agent.entity.ActionType;

/** START_EC2_INSTANCE - one ec2:StartInstances call, for an instance found stopped. */
@Component
public class StartInstanceHandler implements ActionHandler {

    private static final Logger log = LoggerFactory.getLogger(StartInstanceHandler.class);

    private final CloudProvider cloudProvider;

    public StartInstanceHandler(CloudProvider cloudProvider) {
        this.cloudProvider = cloudProvider;
    }

    @Override
    public ActionType actionType() {
        return ActionType.START_EC2_INSTANCE;
    }

    @Override
    public ProviderActionResult execute(Target target) {
        log.info("Starting {} for incident {}", target.providerResourceId(), target.incidentId());
        return cloudProvider.startInstance(target.providerResourceId());
    }
}
