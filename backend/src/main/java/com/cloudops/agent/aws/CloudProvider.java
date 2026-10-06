package com.cloudops.agent.aws;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.cloudops.agent.aws.model.DiscoveredResource;
import com.cloudops.agent.aws.model.MetricSample;
import com.cloudops.agent.aws.model.PermissionCheck;
import com.cloudops.agent.aws.model.ProviderActionResult;
import com.cloudops.agent.aws.model.ProviderIdentity;

/**
 * The only way the application talks to a cloud. Two implementations exist:
 *
 * <ul>
 *   <li>{@link AwsCloudProvider} — the real one, AWS SDK for Java v2 (EC2, CloudWatch,
 *       STS, IAM). Active when {@code cloudops.cloud.provider=aws}.</li>
 *   <li>{@link MockCloudProvider} — a local simulator so the pipeline can be developed
 *       and demonstrated without credentials. Active when
 *       {@code cloudops.cloud.provider=mock}.</li>
 * </ul>
 *
 * Note the deliberate design: the mock is a <em>separate implementation</em>, not fake
 * data injected into the AWS path. The AWS code always makes real API calls.
 *
 * Every method exposes only predefined, parameterised operations — there is no
 * "run this command" entry point anywhere in this interface, which is what makes the
 * AI layer structurally incapable of executing arbitrary cloud commands.
 */
public interface CloudProvider {

    /** "aws" or "mock" — surfaced on the dashboard. */
    String providerName();

    /** True only for the implementation that calls AWS for real. */
    boolean real();

    /** Identity/credential status, used by the System page and startup logging. */
    ProviderIdentity identity();

    /** Instances visible to this account, subject to the discovery tag filter. */
    List<DiscoveredResource> discoverInstances();

    Optional<DiscoveredResource> describeInstance(String instanceId);

    /**
     * Metric datapoints for one instance and one metric.
     *
     * @param statistic CloudWatch statistic name: Average, Maximum, Minimum, Sum
     * @param periodSeconds aggregation period; 300 on the AWS free tier (basic monitoring)
     */
    List<MetricSample> fetchMetricSamples(String instanceId, String metricName, String statistic,
            Instant start, Instant end, int periodSeconds);

    ProviderActionResult rebootInstance(String instanceId);

    ProviderActionResult stopInstance(String instanceId);

    ProviderActionResult startInstance(String instanceId);

    ProviderActionResult tagResource(String instanceId, Map<String, String> tags);

    /**
     * Asks IAM whether the current principal is allowed to perform an action before it
     * is attempted, e.g. {@code ec2:RebootInstances}.
     */
    PermissionCheck checkPermission(String iamAction, String resourceArnOrWildcard);
}
