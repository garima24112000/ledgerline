package com.ledgerline.audit;

import java.time.Duration;
import java.time.ZoneId;
import java.util.Map;

/** Settings from the Lambda's environment variables (set by infra/terraform/lambda.tf). */
public record AuditConfig(
        String dbHost,
        int dbPort,
        String dbName,
        String dbUser,
        String dbPasswordParameter,
        String auditBucket,
        ZoneId zone,
        Duration unknownMaxAge,
        String functionName) {

    public static AuditConfig fromEnvironment(Map<String, String> env) {
        return new AuditConfig(
                required(env, "DB_HOST"),
                Integer.parseInt(env.getOrDefault("DB_PORT", "5432")),
                env.getOrDefault("DB_NAME", "ledgerline"),
                env.getOrDefault("DB_USER", "ledgerline"),
                required(env, "DB_PASSWORD_PARAM"),
                required(env, "AUDIT_BUCKET"),
                ZoneId.of(env.getOrDefault("AUDIT_TIMEZONE", "America/New_York")),
                Duration.ofMinutes(Long.parseLong(env.getOrDefault("UNKNOWN_MAX_AGE_MINUTES", "60"))),
                env.getOrDefault("AWS_LAMBDA_FUNCTION_NAME", "ledgerline-ledger-audit"));
    }

    private static String required(Map<String, String> env, String name) {
        String value = env.get(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("missing environment variable " + name);
        }
        return value;
    }
}
