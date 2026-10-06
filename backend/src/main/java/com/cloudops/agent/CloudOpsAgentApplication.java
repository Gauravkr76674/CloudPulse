package com.cloudops.agent;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Entry point for the Multi-Model AI Agent for Intelligent Cloud Operations.
 *
 * @SpringBootApplication            = @Configuration + @EnableAutoConfiguration + @ComponentScan
 * @EnableScheduling                 = turns on the @Scheduled CloudWatch metric poller
 * @EnableAsync                      = lets the AI analysis pipeline run off the request thread
 * @ConfigurationPropertiesScan      = binds the cloudops.* blocks in application.yml to
 *                                     the @ConfigurationProperties classes in config/
 */
@SpringBootApplication
@EnableScheduling
@EnableAsync
@ConfigurationPropertiesScan
public class CloudOpsAgentApplication {

    public static void main(String[] args) {
        SpringApplication.run(CloudOpsAgentApplication.class, args);
    }
}
