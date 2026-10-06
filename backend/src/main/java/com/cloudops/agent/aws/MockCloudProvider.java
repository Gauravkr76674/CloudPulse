package com.cloudops.agent.aws;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;

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

import jakarta.annotation.PostConstruct;

/**
 * Local simulator used when {@code cloudops.cloud.provider=mock}, so the full pipeline
 * (metrics → detection → incident → AI → approval → action) can be developed and
 * demonstrated with no AWS account and no cost.
 *
 * This is a separate implementation of {@link CloudProvider}; it never feeds data into
 * {@link AwsCloudProvider}, which always talks to the real API.
 *
 * Two behaviours make it useful rather than merely fake:
 * <ul>
 *   <li>Values are deterministic per (instance, metric, timestamp), so re-polling an
 *       overlapping window yields identical datapoints — exactly like CloudWatch, and
 *       required by the unique constraint on the metrics table.</li>
 *   <li>{@code demo-web-01} runs hot (CPU above the 90% threshold) until it is rebooted,
 *       after which CPU settles for 30 minutes. A remediation therefore has a visible
 *       effect on the next poll.</li>
 * </ul>
 */
@Component
@ConditionalOnProperty(name = "cloudops.cloud.provider", havingValue = "mock", matchIfMissing = true)
public class MockCloudProvider implements CloudProvider {

    private static final Logger log = LoggerFactory.getLogger(MockCloudProvider.class);
    private static final String NAMESPACE = "AWS/EC2";
    private static final Duration RECOVERY_WINDOW = Duration.ofMinutes(30);

    private final CloudProperties cloudProperties;
    private final Map<String, MockInstance> instances = new ConcurrentHashMap<>();

    public MockCloudProvider(CloudProperties cloudProperties) {
        this.cloudProperties = cloudProperties;
    }

    @PostConstruct
    void seedInstances() {
        String region = cloudProperties.getRegion();

        // Ids match db/seed.sql so SQL-seeded and app-seeded data line up
        register(new MockInstance("i-0demoweb0000000a1", "demo-web-01", region, region + "a",
                "t3.micro", "10.0.1.21", "13.234.10.5", true));
        register(new MockInstance("i-0demoapi0000000b2", "demo-api-02", region, region + "b",
                "t3.micro", "10.0.2.34", null, false));
        register(new MockInstance("i-0demobatch000000c3", "demo-batch-03", region, region + "a",
                "t3.micro", "10.0.3.77", null, false));

        log.info("Mock cloud provider active with {} simulated EC2 instance(s). "
                + "Set CLOUD_PROVIDER=aws with credentials for real AWS.", instances.size());
    }

    private void register(MockInstance instance) {
        instances.put(instance.instanceId, instance);
    }

    @Override
    public String providerName() {
        return "mock";
    }

    @Override
    public boolean real() {
        return false;
    }

    @Override
    public ProviderIdentity identity() {
        return new ProviderIdentity("mock", true, "000000000000",
                "arn:aws:iam::000000000000:user/local-mock", cloudProperties.getRegion(),
                "local mock provider — no AWS API calls are made");
    }

    @Override
    public List<DiscoveredResource> discoverInstances() {
        List<DiscoveredResource> discovered = new ArrayList<>();
        for (MockInstance instance : instances.values()) {
            discovered.add(instance.toDiscoveredResource());
        }
        discovered.sort((a, b) -> a.resourceId().compareTo(b.resourceId()));
        return discovered;
    }

    @Override
    public Optional<DiscoveredResource> describeInstance(String instanceId) {
        return Optional.ofNullable(instances.get(instanceId))
                .map(MockInstance::toDiscoveredResource);
    }

    @Override
    public List<MetricSample> fetchMetricSamples(String instanceId, String metricName,
            String statistic, Instant start, Instant end, int periodSeconds) {

        MockInstance instance = instances.get(instanceId);
        if (instance == null) {
            return List.of();
        }
        // A stopped instance publishes no CloudWatch datapoints
        if (instance.status != ResourceStatus.RUNNING) {
            return List.of();
        }

        int period = periodSeconds <= 0 ? 300 : periodSeconds;
        long startEpoch = alignDown(start.getEpochSecond(), period);
        long endEpoch = end.getEpochSecond();

        List<MetricSample> samples = new ArrayList<>();
        for (long epoch = startEpoch; epoch <= endEpoch; epoch += period) {
            Instant timestamp = Instant.ofEpochSecond(epoch);
            double value = valueFor(instance, metricName, statistic, timestamp);
            samples.add(new MetricSample(metricName, normalizeStatistic(statistic), NAMESPACE,
                    timestamp, value, unitFor(metricName)));
        }
        return samples;
    }

    @Override
    public ProviderActionResult rebootInstance(String instanceId) {
        MockInstance instance = instances.get(instanceId);
        if (instance == null) {
            return ProviderActionResult.failed("Unknown mock instance " + instanceId);
        }
        instance.lastRebootAt = Instant.now();
        instance.status = ResourceStatus.RUNNING;
        log.info("[mock] rebooted {}; CPU will read normal for the next {} minutes",
                instanceId, RECOVERY_WINDOW.toMinutes());
        return ProviderActionResult.ok("Mock reboot completed for " + instanceId,
                "mock-" + System.currentTimeMillis());
    }

    @Override
    public ProviderActionResult stopInstance(String instanceId) {
        MockInstance instance = instances.get(instanceId);
        if (instance == null) {
            return ProviderActionResult.failed("Unknown mock instance " + instanceId);
        }
        instance.status = ResourceStatus.STOPPED;
        log.info("[mock] stopped {}", instanceId);
        return ProviderActionResult.ok("Mock stop completed for " + instanceId,
                "mock-" + System.currentTimeMillis());
    }

    @Override
    public ProviderActionResult startInstance(String instanceId) {
        MockInstance instance = instances.get(instanceId);
        if (instance == null) {
            return ProviderActionResult.failed("Unknown mock instance " + instanceId);
        }
        instance.status = ResourceStatus.RUNNING;
        instance.lastRebootAt = Instant.now();
        log.info("[mock] started {}", instanceId);
        return ProviderActionResult.ok("Mock start completed for " + instanceId,
                "mock-" + System.currentTimeMillis());
    }

    @Override
    public ProviderActionResult tagResource(String instanceId, Map<String, String> tags) {
        MockInstance instance = instances.get(instanceId);
        if (instance == null) {
            return ProviderActionResult.failed("Unknown mock instance " + instanceId);
        }
        instance.tags.putAll(tags);
        log.info("[mock] tagged {} with {}", instanceId, tags.keySet());
        return ProviderActionResult.ok("Mock tags applied to " + instanceId + ": " + tags.keySet(),
                "mock-" + System.currentTimeMillis());
    }

    @Override
    public PermissionCheck checkPermission(String iamAction, String resourceArnOrWildcard) {
        return PermissionCheck.notEvaluated("mock provider — IAM simulation not applicable");
    }

    // ---------------------------------------------------------------------
    // deterministic metric simulation
    // ---------------------------------------------------------------------

    private double valueFor(MockInstance instance, String metricName, String statistic,
            Instant timestamp) {
        // Same inputs always produce the same output: repeated polls stay consistent
        Random random = new Random(Objects.hash(instance.instanceId, metricName, timestamp.getEpochSecond()));
        boolean recentlyRebooted = instance.lastRebootAt != null
                && Duration.between(instance.lastRebootAt, Instant.now()).compareTo(RECOVERY_WINDOW) < 0;

        double jitter = random.nextDouble();

        switch (metricName) {
            case "CPUUtilization" -> {
                double base;
                if (instance.runsHot && !recentlyRebooted) {
                    base = 92.0 + jitter * 6.0;      // 92-98% — breaches the 90% threshold
                } else if (instance.runsHot) {
                    base = 11.0 + jitter * 9.0;      // settled after a reboot
                } else {
                    base = 22.0 + jitter * 25.0;     // ordinary 22-47%
                }
                if (isMaximum(statistic)) {
                    base = Math.min(100.0, base + 2.5);
                }
                return round(base);
            }
            case "NetworkIn" -> {
                double base = instance.runsHot && !recentlyRebooted
                        ? 52_000_000 + jitter * 18_000_000   // above the 50 MB threshold
                        : 1_500_000 + jitter * 6_000_000;
                return round(base);
            }
            case "NetworkOut" -> {
                double base = 900_000 + jitter * 4_000_000;
                return round(base);
            }
            case "StatusCheckFailed" -> {
                // Rare simulated failure, and only on the hot instance
                boolean failed = instance.runsHot && !recentlyRebooted && jitter > 0.93;
                return failed ? 1.0 : 0.0;
            }
            case "DiskReadBytes", "DiskWriteBytes" -> {
                return round(200_000 + jitter * 2_000_000);
            }
            default -> {
                return round(jitter * 100);
            }
        }
    }

    private static boolean isMaximum(String statistic) {
        return statistic != null && statistic.trim().equalsIgnoreCase("Maximum");
    }

    private static String normalizeStatistic(String statistic) {
        if (statistic == null || statistic.isBlank()) {
            return "Average";
        }
        String trimmed = statistic.trim();
        return trimmed.substring(0, 1).toUpperCase() + trimmed.substring(1).toLowerCase();
    }

    private static String unitFor(String metricName) {
        return switch (metricName) {
            case "CPUUtilization" -> "Percent";
            case "NetworkIn", "NetworkOut", "DiskReadBytes", "DiskWriteBytes" -> "Bytes";
            default -> "Count";
        };
    }

    private static long alignDown(long epochSeconds, int period) {
        return (epochSeconds / period) * period;
    }

    private static double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    /** Mutable in-memory state for one simulated instance. */
    private static final class MockInstance {

        private final String instanceId;
        private final String name;
        private final String region;
        private final String availabilityZone;
        private final String instanceType;
        private final String privateIp;
        private final String publicIp;
        private final boolean runsHot;
        private final Map<String, String> tags = new LinkedHashMap<>();
        private final Instant launchTime = Instant.now().minus(Duration.ofDays(6));

        private volatile ResourceStatus status = ResourceStatus.RUNNING;
        private volatile Instant lastRebootAt;

        private MockInstance(String instanceId, String name, String region, String availabilityZone,
                String instanceType, String privateIp, String publicIp, boolean runsHot) {
            this.instanceId = instanceId;
            this.name = name;
            this.region = region;
            this.availabilityZone = availabilityZone;
            this.instanceType = instanceType;
            this.privateIp = privateIp;
            this.publicIp = publicIp;
            this.runsHot = runsHot;
            this.tags.put("Name", name);
            this.tags.put("Env", "dev");
            this.tags.put("CloudOpsMonitored", "true");
        }

        private DiscoveredResource toDiscoveredResource() {
            return new DiscoveredResource(instanceId, name, ResourceType.EC2_INSTANCE, region,
                    availabilityZone, instanceType, status, new LinkedHashMap<>(tags), privateIp,
                    publicIp, launchTime);
        }
    }
}
