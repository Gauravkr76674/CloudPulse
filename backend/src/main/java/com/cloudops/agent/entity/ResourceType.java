package com.cloudops.agent.entity;

/** Kind of cloud resource under management. Only EC2_INSTANCE is remediable in the MVP. */
public enum ResourceType {
    EC2_INSTANCE,
    RDS_INSTANCE,
    S3_BUCKET,
    LAMBDA_FUNCTION,
    OTHER
}
