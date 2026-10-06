package com.cloudops.agent.aws;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.cloudops.agent.aws.model.DiscoveredResource;
import com.cloudops.agent.aws.model.MetricSample;
import com.cloudops.agent.aws.model.PermissionCheck;
import com.cloudops.agent.aws.model.ProviderActionResult;
import com.cloudops.agent.aws.model.ProviderIdentity;
import com.cloudops.agent.config.CloudProperties;
import com.cloudops.agent.entity.ResourceStatus;
import com.cloudops.agent.entity.ResourceType;
import com.cloudops.agent.exception.CloudProviderException;

import jakarta.annotation.PostConstruct;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.cloudwatch.CloudWatchClient;
import software.amazon.awssdk.services.cloudwatch.model.Datapoint;
import software.amazon.awssdk.services.cloudwatch.model.Dimension;
import software.amazon.awssdk.services.cloudwatch.model.GetMetricStatisticsRequest;
import software.amazon.awssdk.services.cloudwatch.model.GetMetricStatisticsResponse;
import software.amazon.awssdk.services.cloudwatch.model.Statistic;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.CreateTagsRequest;
import software.amazon.awssdk.services.ec2.model.CreateTagsResponse;
import software.amazon.awssdk.services.ec2.model.DescribeInstancesRequest;
import software.amazon.awssdk.services.ec2.model.DescribeInstancesResponse;
import software.amazon.awssdk.services.ec2.model.Filter;
import software.amazon.awssdk.services.ec2.model.Instance;
import software.amazon.awssdk.services.ec2.model.RebootInstancesRequest;
import software.amazon.awssdk.services.ec2.model.RebootInstancesResponse;
import software.amazon.awssdk.services.ec2.model.Reservation;
import software.amazon.awssdk.services.ec2.model.StartInstancesRequest;
import software.amazon.awssdk.services.ec2.model.StartInstancesResponse;
import software.amazon.awssdk.services.ec2.model.StopInstancesRequest;
import software.amazon.awssdk.services.ec2.model.StopInstancesResponse;
import software.amazon.awssdk.services.ec2.model.Tag;
import software.amazon.awssdk.services.iam.IamClient;
import software.amazon.awssdk.services.iam.model.EvaluationResult;
import software.amazon.awssdk.services.iam.model.SimulatePrincipalPolicyResponse;
import software.amazon.awssdk.services.iam.model.SimulatePrincipalPolicyRequest;
import software.amazon.awssdk.services.sts.StsClient;
import software.amazon.awssdk.services.sts.model.GetCallerIdentityResponse;

/**
 * The real AWS implementation, active when {@code cloudops.cloud.provider=aws}.
 *
 * Uses EC2 for discovery and the four whitelisted mutations, CloudWatch for metrics,
 * STS to report the caller identity, and IAM to pre-flight permissions. Only these
 * predefined SDK operations exist — there is no generic "execute" path.
 */
@Component
@ConditionalOnProperty(name = "cloudops.cloud.provider", havingValue = "aws")
public class AwsCloudProvider implements CloudProvider {

    private static final Logger log = LoggerFactory.getLogger(AwsCloudProvider.class);
    private static final String EC2_NAMESPACE = "AWS/EC2";
    private static final String DIMENSION_INSTANCE_ID = "InstanceId";

    private final Ec2Client ec2Client;
    private final CloudWatchClient cloudWatchClient;
    private final IamClient iamClient;
    private final StsClient stsClient;
    private final CloudProperties cloudProperties;

    /** Cached STS identity; refreshed lazily because credentials rarely change at runtime. */
    private volatile ProviderIdentity cachedIdentity;

    public AwsCloudProvider(Ec2Client ec2Client, CloudWatchClient cloudWatchClient,
            IamClient iamClient, StsClient stsClient, CloudProperties cloudProperties) {
        this.ec2Client = ec2Client;
        this.cloudWatchClient = cloudWatchClient;
        this.iamClient = iamClient;
        this.stsClient = stsClient;
        this.cloudProperties = cloudProperties;
    }

    @PostConstruct
    void logIdentityAtStartup() {
        ProviderIdentity current = identity();
        if (current.credentialsAvailable()) {
            log.info("AWS provider active — account {} in region {}", current.accountId(),
                    current.region());
        } else {
            log.warn("AWS provider is selected but credentials are not usable yet: {}. "
                    + "Metric collection and remediation will fail until this is fixed. "
                    + "Set CLOUD_PROVIDER=mock for offline development.", current.message());
        }
    }

    @Override
    public String providerName() {
        return "aws";
    }

    @Override
    public boolean real() {
        return true;
    }

    @Override
    public ProviderIdentity identity() {
        ProviderIdentity snapshot = cachedIdentity;
        if (snapshot != null && snapshot.credentialsAvailable()) {
            return snapshot;
        }
        try {
            GetCallerIdentityResponse response = stsClient.getCallerIdentity();
            ProviderIdentity identity = new ProviderIdentity("aws", true, response.account(),
                    response.arn(), cloudProperties.getRegion(), "credentials resolved");
            this.cachedIdentity = identity;
            return identity;
        } catch (SdkException ex) {
            // Message only: never log or expose the credential material itself
            ProviderIdentity identity = ProviderIdentity.unavailable("aws",
                    cloudProperties.getRegion(), ex.getMessage());
            this.cachedIdentity = identity;
            return identity;
        }
    }

    @Override
    public List<DiscoveredResource> discoverInstances() {
        try {
            DescribeInstancesRequest.Builder request = DescribeInstancesRequest.builder()
                    .maxResults(100);

            if (cloudProperties.isDiscoveryTagFilterEnabled()) {
                request.filters(Filter.builder()
                        .name("tag:" + cloudProperties.getDiscoveryTagKey())
                        .values(cloudProperties.getDiscoveryTagValue())
                        .build());
            }

            List<DiscoveredResource> discovered = new ArrayList<>();
            String nextToken = null;
            do {
                DescribeInstancesResponse response = ec2Client.describeInstances(
                        request.nextToken(nextToken).build());
                for (Reservation reservation : response.reservations()) {
                    for (Instance instance : reservation.instances()) {
                        discovered.add(toDiscoveredResource(instance));
                    }
                }
                nextToken = response.nextToken();
            } while (nextToken != null && !nextToken.isBlank());

            log.debug("EC2 discovery returned {} instance(s)", discovered.size());
            return discovered;

        } catch (SdkException ex) {
            throw new CloudProviderException("EC2 describeInstances failed: " + ex.getMessage(), ex);
        }
    }

    @Override
    public Optional<DiscoveredResource> describeInstance(String instanceId) {
        try {
            DescribeInstancesResponse response = ec2Client.describeInstances(
                    DescribeInstancesRequest.builder().instanceIds(instanceId).build());

            return response.reservations().stream()
                    .flatMap(reservation -> reservation.instances().stream())
                    .findFirst()
                    .map(this::toDiscoveredResource);

        } catch (SdkException ex) {
            // A terminated instance disappears after a while; treat "not found" as empty
            if (ex.getMessage() != null && ex.getMessage().contains("InvalidInstanceID.NotFound")) {
                return Optional.empty();
            }
            throw new CloudProviderException(
                    "EC2 describeInstances(" + instanceId + ") failed: " + ex.getMessage(), ex);
        }
    }

    @Override
    public List<MetricSample> fetchMetricSamples(String instanceId, String metricName,
            String statistic, Instant start, Instant end, int periodSeconds) {
        Statistic awsStatistic = toAwsStatistic(statistic);
        try {
            GetMetricStatisticsRequest request = GetMetricStatisticsRequest.builder()
                    .namespace(EC2_NAMESPACE)
                    .metricName(metricName)
                    .dimensions(Dimension.builder()
                            .name(DIMENSION_INSTANCE_ID)
                            .value(instanceId)
                            .build())
                    .startTime(start)
                    .endTime(end)
                    .period(periodSeconds)
                    .statistics(awsStatistic)
                    .build();

            GetMetricStatisticsResponse response = cloudWatchClient.getMetricStatistics(request);

            List<MetricSample> samples = new ArrayList<>();
            for (Datapoint datapoint : response.datapoints()) {
                Double value = extractValue(datapoint, awsStatistic);
                if (value == null) {
                    continue;
                }
                samples.add(new MetricSample(metricName, awsStatistic.toString(), EC2_NAMESPACE,
                        datapoint.timestamp(), value, datapoint.unitAsString()));
            }
            // CloudWatch returns datapoints unordered
            samples.sort((a, b) -> a.timestamp().compareTo(b.timestamp()));
            return samples;

        } catch (SdkException ex) {
            throw new CloudProviderException("CloudWatch getMetricStatistics for " + instanceId
                    + "/" + metricName + " failed: " + ex.getMessage(), ex);
        }
    }

    @Override
    public ProviderActionResult rebootInstance(String instanceId) {
        try {
            RebootInstancesResponse response = ec2Client.rebootInstances(
                    RebootInstancesRequest.builder().instanceIds(instanceId).build());
            String requestId = response.responseMetadata().requestId();
            log.info("Issued EC2 reboot for {} (requestId {})", instanceId, requestId);
            return ProviderActionResult.ok("Reboot requested for " + instanceId, requestId);
        } catch (SdkException ex) {
            log.error("EC2 reboot of {} failed: {}", instanceId, ex.getMessage());
            return ProviderActionResult.failed("EC2 rebootInstances failed: " + ex.getMessage());
        }
    }

    @Override
    public ProviderActionResult stopInstance(String instanceId) {
        try {
            StopInstancesResponse response = ec2Client.stopInstances(
                    StopInstancesRequest.builder().instanceIds(instanceId).build());
            String requestId = response.responseMetadata().requestId();
            String state = response.stoppingInstances().isEmpty()
                    ? "unknown"
                    : response.stoppingInstances().get(0).currentState().nameAsString();
            log.info("Issued EC2 stop for {} (state {}, requestId {})", instanceId, state, requestId);
            return ProviderActionResult.ok("Stop requested for " + instanceId + "; state=" + state,
                    requestId);
        } catch (SdkException ex) {
            log.error("EC2 stop of {} failed: {}", instanceId, ex.getMessage());
            return ProviderActionResult.failed("EC2 stopInstances failed: " + ex.getMessage());
        }
    }

    @Override
    public ProviderActionResult startInstance(String instanceId) {
        try {
            StartInstancesResponse response = ec2Client.startInstances(
                    StartInstancesRequest.builder().instanceIds(instanceId).build());
            String requestId = response.responseMetadata().requestId();
            String state = response.startingInstances().isEmpty()
                    ? "unknown"
                    : response.startingInstances().get(0).currentState().nameAsString();
            log.info("Issued EC2 start for {} (state {}, requestId {})", instanceId, state, requestId);
            return ProviderActionResult.ok("Start requested for " + instanceId + "; state=" + state,
                    requestId);
        } catch (SdkException ex) {
            log.error("EC2 start of {} failed: {}", instanceId, ex.getMessage());
            return ProviderActionResult.failed("EC2 startInstances failed: " + ex.getMessage());
        }
    }

    @Override
    public ProviderActionResult tagResource(String instanceId, Map<String, String> tags) {
        try {
            List<Tag> awsTags = tags.entrySet().stream()
                    .map(entry -> Tag.builder().key(entry.getKey()).value(entry.getValue()).build())
                    .toList();

            CreateTagsResponse response = ec2Client.createTags(CreateTagsRequest.builder()
                    .resources(instanceId)
                    .tags(awsTags)
                    .build());

            String requestId = response.responseMetadata().requestId();
            log.info("Tagged {} with {} (requestId {})", instanceId, tags.keySet(), requestId);
            return ProviderActionResult.ok("Applied tags " + tags.keySet() + " to " + instanceId,
                    requestId);
        } catch (SdkException ex) {
            log.error("EC2 createTags on {} failed: {}", instanceId, ex.getMessage());
            return ProviderActionResult.failed("EC2 createTags failed: " + ex.getMessage());
        }
    }

    @Override
    public PermissionCheck checkPermission(String iamAction, String resourceArnOrWildcard) {
        ProviderIdentity current = identity();
        if (!current.credentialsAvailable()) {
            return PermissionCheck.notEvaluated("no usable AWS credentials");
        }

        String principalArn = toSimulatablePrincipal(current.arn(), current.accountId());
        if (principalArn == null) {
            return PermissionCheck.notEvaluated(
                    "caller ARN " + current.arn() + " cannot be simulated by IAM");
        }

        try {
            SimulatePrincipalPolicyResponse response = iamClient.simulatePrincipalPolicy(
                    SimulatePrincipalPolicyRequest.builder()
                            .policySourceArn(principalArn)
                            .actionNames(iamAction)
                            .resourceArns(resourceArnOrWildcard)
                            .build());

            Optional<EvaluationResult> result = response.evaluationResults().stream().findFirst();
            if (result.isEmpty()) {
                return PermissionCheck.notEvaluated("IAM returned no evaluation result");
            }

            String decision = result.get().evalDecisionAsString();
            return "allowed".equalsIgnoreCase(decision)
                    ? PermissionCheck.allowed(iamAction)
                    : PermissionCheck.denied(iamAction, decision);

        } catch (SdkException ex) {
            // Simulation itself needs iam:SimulatePrincipalPolicy; if it is missing we do
            // not block the action — AWS will still enforce the real permission.
            log.debug("IAM preflight for {} could not run: {}", iamAction, ex.getMessage());
            return PermissionCheck.notEvaluated("IAM simulation unavailable: " + ex.getMessage());
        }
    }

    /** Builds the ARN of an EC2 instance for the IAM pre-flight check. */
    public String instanceArn(String instanceId) {
        ProviderIdentity current = identity();
        if (!current.credentialsAvailable() || current.accountId() == null) {
            return "*";
        }
        return "arn:aws:ec2:" + cloudProperties.getRegion() + ":" + current.accountId()
                + ":instance/" + instanceId;
    }

    // ---------------------------------------------------------------------
    // helpers
    // ---------------------------------------------------------------------

    private DiscoveredResource toDiscoveredResource(Instance instance) {
        Map<String, String> tags = new HashMap<>();
        for (Tag tag : instance.tags()) {
            tags.put(tag.key(), tag.value());
        }

        String name = tags.getOrDefault("Name", instance.instanceId());
        String availabilityZone = instance.placement() == null
                ? null
                : instance.placement().availabilityZone();
        ResourceStatus status = instance.state() == null
                ? ResourceStatus.UNKNOWN
                : ResourceStatus.fromAws(instance.state().nameAsString());

        return new DiscoveredResource(
                instance.instanceId(),
                name,
                ResourceType.EC2_INSTANCE,
                cloudProperties.getRegion(),
                availabilityZone,
                instance.instanceTypeAsString(),
                status,
                tags,
                instance.privateIpAddress(),
                instance.publicIpAddress(),
                instance.launchTime());
    }

    private static Statistic toAwsStatistic(String statistic) {
        if (statistic == null) {
            return Statistic.AVERAGE;
        }
        return switch (statistic.trim().toUpperCase()) {
            case "MAXIMUM", "MAX" -> Statistic.MAXIMUM;
            case "MINIMUM", "MIN" -> Statistic.MINIMUM;
            case "SUM" -> Statistic.SUM;
            case "SAMPLECOUNT", "SAMPLE_COUNT" -> Statistic.SAMPLE_COUNT;
            default -> Statistic.AVERAGE;
        };
    }

    private static Double extractValue(Datapoint datapoint, Statistic statistic) {
        return switch (statistic) {
            case MAXIMUM -> datapoint.maximum();
            case MINIMUM -> datapoint.minimum();
            case SUM -> datapoint.sum();
            case SAMPLE_COUNT -> datapoint.sampleCount();
            default -> datapoint.average();
        };
    }

    /**
     * IAM can simulate users and roles, but not the {@code sts:assumed-role} ARN that a
     * running EC2 instance presents. Convert that form back to the underlying role ARN.
     */
    private static String toSimulatablePrincipal(String callerArn, String accountId) {
        if (callerArn == null) {
            return null;
        }
        if (callerArn.contains(":assumed-role/")) {
            String[] parts = callerArn.split(":assumed-role/");
            if (parts.length < 2 || accountId == null) {
                return null;
            }
            String roleName = parts[1].split("/")[0];
            return "arn:aws:iam::" + accountId + ":role/" + roleName;
        }
        if (callerArn.contains(":user/") || callerArn.contains(":role/")) {
            return callerArn;
        }
        // Root account credentials cannot be simulated
        return null;
    }
}
