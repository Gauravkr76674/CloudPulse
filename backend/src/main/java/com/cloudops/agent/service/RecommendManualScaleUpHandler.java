package com.cloudops.agent.service;

import org.springframework.stereotype.Component;

import com.cloudops.agent.aws.model.ProviderActionResult;
import com.cloudops.agent.entity.ActionType;

/**
 * RECOMMEND_MANUAL_SCALE_UP — advisory only, and deliberately so.
 *
 * Resizing an instance means stopping it, changing its type and starting it again, and it can
 * move an account off the free tier. That is a decision with a cost attached, so the agent
 * records the advice and a human performs it. Executing this action writes the advice into the
 * action history; it never calls ec2:ModifyInstanceAttribute.
 */
@Component
public class RecommendManualScaleUpHandler implements ActionHandler {

    @Override
    public ActionType actionType() {
        return ActionType.RECOMMEND_MANUAL_SCALE_UP;
    }

    @Override
    public ProviderActionResult execute(Target target) {
        return ProviderActionResult.ok(
                "Advisory recorded: " + target.resourceName() + " (" + target.providerResourceId()
                        + ") should be resized by an operator. The agent made no AWS call.",
                null);
    }
}
