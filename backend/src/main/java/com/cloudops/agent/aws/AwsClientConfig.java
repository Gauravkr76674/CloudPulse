package com.cloudops.agent.aws;

import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.cloudops.agent.config.CloudProperties;

import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.ProfileCredentialsProvider;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cloudwatch.CloudWatchClient;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.iam.IamClient;
import software.amazon.awssdk.services.sts.StsClient;

/**
 * AWS SDK v2 clients, created only when {@code cloudops.cloud.provider=aws}.
 *
 * Credentials are never read from application.yml or from code. The SDK's
 * {@link DefaultCredentialsProvider} resolves them in order from environment variables
 * (AWS_ACCESS_KEY_ID / AWS_SECRET_ACCESS_KEY), the shared credentials file
 * (~/.aws/credentials), and finally the EC2/ECS instance role. Set
 * {@code cloudops.cloud.profile} (AWS_PROFILE) to pin a named profile instead.
 */
@Configuration
@ConditionalOnProperty(name = "cloudops.cloud.provider", havingValue = "aws")
public class AwsClientConfig {

    private static final Logger log = LoggerFactory.getLogger(AwsClientConfig.class);

    private final CloudProperties cloudProperties;

    public AwsClientConfig(CloudProperties cloudProperties) {
        this.cloudProperties = cloudProperties;
    }

    @Bean
    public AwsCredentialsProvider awsCredentialsProvider() {
        String profile = cloudProperties.getProfile();
        if (profile != null && !profile.isBlank()) {
            log.info("Using AWS profile '{}' for credentials", profile);
            return ProfileCredentialsProvider.create(profile);
        }
        log.info("Using the AWS default credentials provider chain");
        return DefaultCredentialsProvider.create();
    }

    @Bean
    public ClientOverrideConfiguration awsOverrideConfiguration() {
        Duration timeout = Duration.ofSeconds(cloudProperties.getApiTimeoutSeconds());
        return ClientOverrideConfiguration.builder()
                .apiCallTimeout(timeout)
                .apiCallAttemptTimeout(timeout)
                .build();
    }

    @Bean(destroyMethod = "close")
    public Ec2Client ec2Client(AwsCredentialsProvider credentialsProvider,
            ClientOverrideConfiguration overrideConfiguration) {
        return Ec2Client.builder()
                .region(Region.of(cloudProperties.getRegion()))
                .credentialsProvider(credentialsProvider)
                .overrideConfiguration(overrideConfiguration)
                .build();
    }

    @Bean(destroyMethod = "close")
    public CloudWatchClient cloudWatchClient(AwsCredentialsProvider credentialsProvider,
            ClientOverrideConfiguration overrideConfiguration) {
        return CloudWatchClient.builder()
                .region(Region.of(cloudProperties.getRegion()))
                .credentialsProvider(credentialsProvider)
                .overrideConfiguration(overrideConfiguration)
                .build();
    }

    /** IAM is a global service, hence Region.AWS_GLOBAL. */
    @Bean(destroyMethod = "close")
    public IamClient iamClient(AwsCredentialsProvider credentialsProvider,
            ClientOverrideConfiguration overrideConfiguration) {
        return IamClient.builder()
                .region(Region.AWS_GLOBAL)
                .credentialsProvider(credentialsProvider)
                .overrideConfiguration(overrideConfiguration)
                .build();
    }

    @Bean(destroyMethod = "close")
    public StsClient stsClient(AwsCredentialsProvider credentialsProvider,
            ClientOverrideConfiguration overrideConfiguration) {
        return StsClient.builder()
                .region(Region.of(cloudProperties.getRegion()))
                .credentialsProvider(credentialsProvider)
                .overrideConfiguration(overrideConfiguration)
                .build();
    }
}
