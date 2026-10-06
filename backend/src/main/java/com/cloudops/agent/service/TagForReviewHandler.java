package com.cloudops.agent.service;

import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.cloudops.agent.aws.CloudProvider;
import com.cloudops.agent.aws.model.ProviderActionResult;
import com.cloudops.agent.entity.ActionType;

/**
 * TAG_FOR_REVIEW — one ec2:CreateTags call.
 *
 * The tag keys and values are built here, from the incident id. Nothing about the tag comes
 * from the model, so a suggestion cannot smuggle content into the AWS request.
 */
@Component
public class TagForReviewHandler implements ActionHandler {

    private static final Logger log = LoggerFactory.getLogger(TagForReviewHandler.class);

    private final CloudProvider cloudProvider;

    public TagForReviewHandler(CloudProvider cloudProvider) {
        this.cloudProvider = cloudProvider;
    }

    @Override
    public ActionType actionType() {
        return ActionType.TAG_FOR_REVIEW;
    }

    @Override
    public ProviderActionResult execute(Target target) {
        Map<String, String> tags = new LinkedHashMap<>();
        tags.put("CloudOpsReview", "true");
        tags.put("CloudOpsIncident", String.valueOf(target.incidentId()));
        log.info("Tagging {} for review (incident {})", target.providerResourceId(),
                target.incidentId());
        return cloudProvider.tagResource(target.providerResourceId(), tags);
    }
}
