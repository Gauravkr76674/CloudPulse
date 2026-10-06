package com.cloudops.agent.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.cloudops.agent.aws.CloudProvider;
import com.cloudops.agent.aws.model.ProviderActionResult;
import com.cloudops.agent.entity.ActionType;

/**
 * REBOOT_EC2_INSTANCE — one ec2:RebootInstances call, nothing else.
 *
 * This is the action the project's main scenario ends in: sustained high CPU on an instance
 * that a reboot is expected to clear. It is reached only after an ADMIN approves, and the
 * instance state was re-checked immediately beforehand.
 */
@Component
public class RebootInstanceHandler implements ActionHandler {

    private static final Logger log = LoggerFactory.getLogger(RebootInstanceHandler.class);

    private final CloudProvider cloudProvider;

    public RebootInstanceHandler(CloudProvider cloudProvider) {
        this.cloudProvider = cloudProvider;
    }

    @Override
    public ActionType actionType() {
        return ActionType.REBOOT_EC2_INSTANCE;
    }

    @Override
    public ProviderActionResult execute(Target target) {
        log.info("Rebooting {} for incident {}", target.providerResourceId(), target.incidentId());
        return cloudProvider.rebootInstance(target.providerResourceId());
    }
}
