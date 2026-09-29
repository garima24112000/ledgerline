package com.ledgerline.audit;

import java.util.List;

/**
 * The JSON document written to S3 as audits/YYYY-MM-DD.json.
 *
 * @param auditDate  the calendar date of the run in the configured time zone (America/New_York by default)
 * @param startedAt  UTC timestamp, ISO-8601
 * @param failures   number of checks that failed; this is the LedgerAuditFailures metric
 */
public record AuditReport(
        String auditDate,
        String startedAt,
        long durationMs,
        boolean passed,
        int failures,
        List<CheckResult> checks) {

    static AuditReport of(String auditDate, String startedAt, long durationMs, List<CheckResult> checks) {
        int failures = (int) checks.stream().filter(check -> !check.passed()).count();
        return new AuditReport(auditDate, startedAt, durationMs, failures == 0, failures, List.copyOf(checks));
    }

    /** S3 key of this report. A second run on the same day overwrites it: the latest run wins. */
    String s3Key() {
        return "audits/" + auditDate + ".json";
    }
}
