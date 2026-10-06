package com.cloudops.agent.service;

import com.cloudops.agent.aws.model.ProviderActionResult;
import com.cloudops.agent.entity.ActionType;

/**
 * One handler per {@link ActionType}. Each implementation performs exactly one predefined
 * operation with parameters that come from the database — never from model output.
 *
 * Three properties are what make this safe, and they are all enforced by the type system
 * rather than by convention:
 *
 * <ol>
 *   <li>A handler is chosen by {@link ActionType}, an enum. There is no string dispatch and
 *       therefore no way for text from an AI reply to select behaviour that is not in the
 *       whitelist.</li>
 *   <li>{@link Target} carries only identifiers we looked up ourselves. There is no field a
 *       command, script, shell fragment or arbitrary parameter could travel in.</li>
 *   <li>No handler builds a request from free text. Each one calls a single, named
 *       {@code CloudProvider} method.</li>
 * </ol>
 */
public interface ActionHandler {

    /** The single action this handler is responsible for. */
    ActionType actionType();

    /**
     * Everything a handler is allowed to know. Assembled by
     * {@link ActionExecutionService} from database rows after every guardrail has passed.
     *
     * @param providerResourceId the AWS instance id, e.g. i-0abc123
     */
    record Target(
            Long actionExecutionId,
            Long incidentId,
            Long resourceId,
            String providerResourceId,
            String resourceName,
            ActionType actionType,
            boolean dryRun) {
    }

    /**
     * Performs the action. Implementations should return
     * {@link ProviderActionResult#failed(String)} rather than throwing for expected
     * provider-side failures; anything thrown is caught by the executor and recorded as a
     * FAILED action.
     */
    ProviderActionResult execute(Target target);
}
