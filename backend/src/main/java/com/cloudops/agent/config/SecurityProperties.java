package com.cloudops.agent.config;

import java.util.ArrayList;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Binds the <code>cloudops.security.*</code> block of application.yml.
 * Secrets arrive from environment variables, never from committed code.
 */
@ConfigurationProperties(prefix = "cloudops.security")
public class SecurityProperties {

    private String jwtSecret;
    private int jwtExpirationMinutes = 120;
    private String issuer = "cloudops-ai-agent";
    private List<String> corsAllowedOrigins = new ArrayList<>();
    private String bootstrapAdminUsername = "admin";
    private String bootstrapAdminPassword;
    private String bootstrapAdminEmail = "admin@cloudops.local";
    private boolean seedDemoUsers = false;

    public String getJwtSecret() {
        return jwtSecret;
    }

    public void setJwtSecret(String jwtSecret) {
        this.jwtSecret = jwtSecret;
    }

    public int getJwtExpirationMinutes() {
        return jwtExpirationMinutes;
    }

    public void setJwtExpirationMinutes(int jwtExpirationMinutes) {
        this.jwtExpirationMinutes = jwtExpirationMinutes;
    }

    public String getIssuer() {
        return issuer;
    }

    public void setIssuer(String issuer) {
        this.issuer = issuer;
    }

    public List<String> getCorsAllowedOrigins() {
        return corsAllowedOrigins;
    }

    public void setCorsAllowedOrigins(List<String> corsAllowedOrigins) {
        this.corsAllowedOrigins = corsAllowedOrigins;
    }

    public String getBootstrapAdminUsername() {
        return bootstrapAdminUsername;
    }

    public void setBootstrapAdminUsername(String bootstrapAdminUsername) {
        this.bootstrapAdminUsername = bootstrapAdminUsername;
    }

    public String getBootstrapAdminPassword() {
        return bootstrapAdminPassword;
    }

    public void setBootstrapAdminPassword(String bootstrapAdminPassword) {
        this.bootstrapAdminPassword = bootstrapAdminPassword;
    }

    public String getBootstrapAdminEmail() {
        return bootstrapAdminEmail;
    }

    public void setBootstrapAdminEmail(String bootstrapAdminEmail) {
        this.bootstrapAdminEmail = bootstrapAdminEmail;
    }

    public boolean isSeedDemoUsers() {
        return seedDemoUsers;
    }

    public void setSeedDemoUsers(boolean seedDemoUsers) {
        this.seedDemoUsers = seedDemoUsers;
    }
}
