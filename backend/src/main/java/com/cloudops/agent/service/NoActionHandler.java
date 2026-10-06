package com.cloudops.agent.service;

import org.springframework.stereotype.Component;

import com.cloudops.agent.aws.model.ProviderActionResult;
import com.cloudops.agent.entity.ActionType;

/**
 * NO_ACTION — records that the anomaly needs no intervention.
 *
 * Having this in the catalog matters: it gives the AI a valid way to say "do nothing", so the
 * decision stage is never pushed towards inventing an action just to produce an answer.
 */
@Component
public class NoActionHandler implements ActionHandler {

    @Override
    public ActionType actionType() {
        return ActionType.NO_ACTION;
    }

    @Override
    public ProviderActionResult execute(Target target) {
        return ProviderActionResult.ok(
                "No action taken: the anomaly on " + target.resourceName()
                        + " was judged benign or self-recovering.", null);
    }
}
