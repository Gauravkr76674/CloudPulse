-- ---------------------------------------------------------------------------
-- Multi-Model AI Agent for Intelligent Cloud Operations
-- Authoritative MySQL 8 schema. Matches the JPA entities in
-- com.cloudops.agent.entity exactly (Hibernate ddl-auto=update will produce the
-- same structure, this file is what you hand in / run on a clean database).
--
--   mysql -u root -p < db/schema.sql
-- ---------------------------------------------------------------------------

CREATE DATABASE IF NOT EXISTS cloudops
    CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE cloudops;

-- ------------------------------------------------------------------ users ---
CREATE TABLE IF NOT EXISTS users (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    username      VARCHAR(60)  NOT NULL,
    email         VARCHAR(150) NOT NULL,
    password_hash VARCHAR(100) NOT NULL,
    full_name     VARCHAR(120) DEFAULT NULL,
    role          VARCHAR(20)  NOT NULL DEFAULT 'VIEWER',
    enabled       BIT(1)       NOT NULL DEFAULT b'1',
    created_at    DATETIME(6)  NOT NULL,
    updated_at    DATETIME(6)  DEFAULT NULL,
    last_login_at DATETIME(6)  DEFAULT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_users_username (username),
    UNIQUE KEY uk_users_email (email)
) ENGINE=InnoDB;

-- -------------------------------------------------------- cloud_resources ---
CREATE TABLE IF NOT EXISTS cloud_resources (
    id                           BIGINT       NOT NULL AUTO_INCREMENT,
    resource_id                  VARCHAR(120) NOT NULL,
    name                         VARCHAR(150) NOT NULL,
    type                         VARCHAR(30)  NOT NULL DEFAULT 'EC2_INSTANCE',
    region                       VARCHAR(30)  NOT NULL,
    availability_zone            VARCHAR(40)  DEFAULT NULL,
    instance_type                VARCHAR(40)  DEFAULT NULL,
    status                       VARCHAR(20)  NOT NULL DEFAULT 'UNKNOWN',
    monitored                    BIT(1)       NOT NULL DEFAULT b'1',
    source                       VARCHAR(20)  NOT NULL DEFAULT 'MANUAL',
    tags_json                    TEXT         DEFAULT NULL,
    private_ip                   VARCHAR(45)  DEFAULT NULL,
    public_ip                    VARCHAR(45)  DEFAULT NULL,
    launch_time                  DATETIME(6)  DEFAULT NULL,
    cpu_threshold_percent        DOUBLE       DEFAULT NULL,
    network_in_threshold_bytes   DOUBLE       DEFAULT NULL,
    consecutive_breaches_to_open INT          DEFAULT NULL,
    last_checked_at              DATETIME(6)  DEFAULT NULL,
    created_by                   VARCHAR(60)  DEFAULT NULL,
    created_at                   DATETIME(6)  NOT NULL,
    updated_at                   DATETIME(6)  DEFAULT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_resource_id (resource_id),
    KEY idx_resource_monitored (monitored),
    KEY idx_resource_type (type)
) ENGINE=InnoDB;

-- ---------------------------------------------------------------- metrics ---
CREATE TABLE IF NOT EXISTS metrics (
    id               BIGINT      NOT NULL AUTO_INCREMENT,
    resource_id      BIGINT      NOT NULL,
    metric_name      VARCHAR(60) NOT NULL,
    namespace        VARCHAR(40) NOT NULL DEFAULT 'AWS/EC2',
    statistic        VARCHAR(20) NOT NULL DEFAULT 'Average',
    metric_value     DOUBLE      NOT NULL,
    unit             VARCHAR(30) DEFAULT NULL,
    metric_timestamp DATETIME(6) NOT NULL,
    collected_at     DATETIME(6) NOT NULL,
    anomalous        BIT(1)      NOT NULL DEFAULT b'0',
    threshold_value  DOUBLE      DEFAULT NULL,
    baseline_mean    DOUBLE      DEFAULT NULL,
    baseline_std_dev DOUBLE      DEFAULT NULL,
    z_score          DOUBLE      DEFAULT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_metric_point (resource_id, metric_name, statistic, metric_timestamp),
    KEY idx_metric_lookup (resource_id, metric_name, metric_timestamp),
    KEY idx_metric_anomalous (anomalous),
    CONSTRAINT fk_metric_resource FOREIGN KEY (resource_id) REFERENCES cloud_resources (id)
) ENGINE=InnoDB;

-- -------------------------------------------------------------- incidents ---
CREATE TABLE IF NOT EXISTS incidents (
    id               BIGINT       NOT NULL AUTO_INCREMENT,
    resource_id      BIGINT       NOT NULL,
    title            VARCHAR(200) NOT NULL,
    description      TEXT         DEFAULT NULL,
    status           VARCHAR(20)  NOT NULL DEFAULT 'OPEN',
    severity         VARCHAR(20)  NOT NULL DEFAULT 'MEDIUM',
    metric_name      VARCHAR(60)  NOT NULL,
    rule_name        VARCHAR(40)  NOT NULL,
    trigger_value    DOUBLE       DEFAULT NULL,
    threshold_value  DOUBLE       DEFAULT NULL,
    dedup_key        VARCHAR(200) NOT NULL,
    -- 1 while the incident is active, set to the incident id once closed, so that
    -- (dedup_key, active_flag) can only collide on a live incident.
    active_flag      BIGINT       NOT NULL DEFAULT 1,
    occurrence_count INT          NOT NULL DEFAULT 1,
    opened_at        DATETIME(6)  NOT NULL,
    last_seen_at     DATETIME(6)  DEFAULT NULL,
    acknowledged_at  DATETIME(6)  DEFAULT NULL,
    acknowledged_by  VARCHAR(60)  DEFAULT NULL,
    resolved_at      DATETIME(6)  DEFAULT NULL,
    resolved_by      VARCHAR(60)  DEFAULT NULL,
    resolution_note  TEXT         DEFAULT NULL,
    updated_at       DATETIME(6)  DEFAULT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_incident_active_dedup (dedup_key, active_flag),
    KEY idx_incident_status (status),
    KEY idx_incident_resource (resource_id),
    KEY idx_incident_opened (opened_at),
    CONSTRAINT fk_incident_resource FOREIGN KEY (resource_id) REFERENCES cloud_resources (id)
) ENGINE=InnoDB;

-- ------------------------------------------------------------ ai_analyses ---
CREATE TABLE IF NOT EXISTS ai_analyses (
    id                 BIGINT       NOT NULL AUTO_INCREMENT,
    incident_id        BIGINT       NOT NULL,
    stage              VARCHAR(20)  NOT NULL,
    provider           VARCHAR(20)  NOT NULL,
    model_name         VARCHAR(80)  NOT NULL,
    summary            VARCHAR(500) DEFAULT NULL,
    root_cause         TEXT         DEFAULT NULL,
    reasoning          TEXT         DEFAULT NULL,
    confidence         DOUBLE       DEFAULT NULL,
    suggested_severity VARCHAR(20)  DEFAULT NULL,
    raw_response       TEXT         DEFAULT NULL,
    prompt_tokens      INT          DEFAULT NULL,
    completion_tokens  INT          DEFAULT NULL,
    latency_ms         BIGINT       DEFAULT NULL,
    successful         BIT(1)       NOT NULL DEFAULT b'1',
    error_message      VARCHAR(500) DEFAULT NULL,
    created_at         DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    KEY idx_analysis_incident (incident_id),
    KEY idx_analysis_stage (stage),
    CONSTRAINT fk_analysis_incident FOREIGN KEY (incident_id) REFERENCES incidents (id)
) ENGINE=InnoDB;

-- -------------------------------------------------------- recommendations ---
CREATE TABLE IF NOT EXISTS recommendations (
    id                  BIGINT       NOT NULL AUTO_INCREMENT,
    incident_id         BIGINT       NOT NULL,
    analysis_id         BIGINT       DEFAULT NULL,
    -- NULL only when the AI proposed an action outside the safe catalog
    action_type         VARCHAR(40)  DEFAULT NULL,
    ai_suggested_action VARCHAR(200) DEFAULT NULL,
    title               VARCHAR(200) NOT NULL,
    rationale           TEXT         DEFAULT NULL,
    expected_impact     TEXT         DEFAULT NULL,
    risk_level          VARCHAR(20)  NOT NULL DEFAULT 'MEDIUM',
    confidence          DOUBLE       DEFAULT NULL,
    priority            INT          NOT NULL DEFAULT 5,
    status              VARCHAR(20)  NOT NULL DEFAULT 'PROPOSED',
    requires_approval   BIT(1)       NOT NULL DEFAULT b'1',
    parameters_json     TEXT         DEFAULT NULL,
    rejection_reason    VARCHAR(500) DEFAULT NULL,
    decided_by          VARCHAR(60)  DEFAULT NULL,
    decided_at          DATETIME(6)  DEFAULT NULL,
    decision_note       VARCHAR(500) DEFAULT NULL,
    created_at          DATETIME(6)  NOT NULL,
    updated_at          DATETIME(6)  DEFAULT NULL,
    PRIMARY KEY (id),
    KEY idx_recommendation_incident (incident_id),
    KEY idx_recommendation_status (status),
    CONSTRAINT fk_recommendation_incident FOREIGN KEY (incident_id) REFERENCES incidents (id),
    CONSTRAINT fk_recommendation_analysis FOREIGN KEY (analysis_id) REFERENCES ai_analyses (id)
) ENGINE=InnoDB;

-- ------------------------------------------------------ action_executions ---
CREATE TABLE IF NOT EXISTS action_executions (
    id                  BIGINT       NOT NULL AUTO_INCREMENT,
    incident_id         BIGINT       NOT NULL,
    recommendation_id   BIGINT       DEFAULT NULL,
    resource_id         BIGINT       NOT NULL,
    action_type         VARCHAR(40)  NOT NULL,
    status              VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    dry_run             BIT(1)       NOT NULL DEFAULT b'0',
    requested_by        VARCHAR(60)  DEFAULT NULL,
    approved_by         VARCHAR(60)  DEFAULT NULL,
    parameters_json     TEXT         DEFAULT NULL,
    result_message      TEXT         DEFAULT NULL,
    error_message       TEXT         DEFAULT NULL,
    blocked_reason      VARCHAR(500) DEFAULT NULL,
    provider_request_id VARCHAR(100) DEFAULT NULL,
    started_at          DATETIME(6)  DEFAULT NULL,
    finished_at         DATETIME(6)  DEFAULT NULL,
    duration_ms         BIGINT       DEFAULT NULL,
    created_at          DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    KEY idx_action_incident (incident_id),
    KEY idx_action_resource_time (resource_id, created_at),
    KEY idx_action_status (status),
    CONSTRAINT fk_action_incident FOREIGN KEY (incident_id) REFERENCES incidents (id),
    CONSTRAINT fk_action_recommendation FOREIGN KEY (recommendation_id) REFERENCES recommendations (id),
    CONSTRAINT fk_action_resource FOREIGN KEY (resource_id) REFERENCES cloud_resources (id)
) ENGINE=InnoDB;

-- ---------------------------------------------------------- notifications ---
CREATE TABLE IF NOT EXISTS notifications (
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    recipient_id BIGINT       DEFAULT NULL,
    broadcast    BIT(1)       NOT NULL DEFAULT b'1',
    type         VARCHAR(30)  NOT NULL,
    severity     VARCHAR(20)  NOT NULL DEFAULT 'LOW',
    title        VARCHAR(200) NOT NULL,
    message      TEXT         DEFAULT NULL,
    incident_id  BIGINT       DEFAULT NULL,
    read_flag    BIT(1)       NOT NULL DEFAULT b'0',
    read_at      DATETIME(6)  DEFAULT NULL,
    created_at   DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    KEY idx_notification_recipient (recipient_id),
    KEY idx_notification_unread (read_flag, created_at),
    CONSTRAINT fk_notification_user FOREIGN KEY (recipient_id) REFERENCES users (id),
    CONSTRAINT fk_notification_incident FOREIGN KEY (incident_id) REFERENCES incidents (id)
) ENGINE=InnoDB;

-- ------------------------------------------------------------- audit_logs ---
CREATE TABLE IF NOT EXISTS audit_logs (
    id          BIGINT      NOT NULL AUTO_INCREMENT,
    username    VARCHAR(60) NOT NULL DEFAULT 'system',
    role        VARCHAR(20) DEFAULT NULL,
    action      VARCHAR(60) NOT NULL,
    target_type VARCHAR(40) DEFAULT NULL,
    target_id   VARCHAR(60) DEFAULT NULL,
    outcome     VARCHAR(20) NOT NULL DEFAULT 'SUCCESS',
    details     TEXT        DEFAULT NULL,
    ip_address  VARCHAR(60) DEFAULT NULL,
    created_at  DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    KEY idx_audit_created (created_at),
    KEY idx_audit_username (username),
    KEY idx_audit_action (action)
) ENGINE=InnoDB;
