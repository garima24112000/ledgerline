package com.ledgerline.audit;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestStreamHandler;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.sql.SQLException;
import java.time.Clock;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.ssm.SsmClient;

/**
 * Lambda entry point: com.ledgerline.audit.LedgerAuditHandler::handleRequest.
 * Triggered nightly by EventBridge Scheduler, or by hand with `aws lambda invoke`. The input is ignored;
 * the response is the report as JSON. A stream handler, so the JSON is exactly what Jackson writes here
 * rather than whatever the runtime's own serializer does with records.
 *
 * A failed check is a successful invocation: the report is written and LedgerAuditFailures > 0 raises
 * the alarm. An exception (database unreachable, S3 denied) fails the invocation instead, which the
 * Lambda Errors alarm catches, so "the audit didn't run" is never mistaken for "the ledger is fine".
 */
public final class LedgerAuditHandler implements RequestStreamHandler {

    private final LedgerAuditor auditor;
    private final ConnectionFactory connections;
    private final ReportStore reports;
    private final String functionName;
    private final Clock clock;
    private final PrintStream stdout;
    private final ObjectMapper json = new ObjectMapper();

    /** Used by the Lambda runtime. Clients are built once per execution environment and reused. */
    public LedgerAuditHandler() {
        this(AuditConfig.fromEnvironment(System.getenv()));
    }

    private LedgerAuditHandler(AuditConfig config) {
        this(new LedgerAuditor(config.unknownMaxAge(), config.zone(), Clock.systemUTC()),
                new JdbcConnectionFactory(config, new SsmPasswordSource(
                        SsmClient.builder().httpClient(UrlConnectionHttpClient.create()).build(),
                        config.dbPasswordParameter())),
                new S3ReportStore(
                        S3Client.builder().httpClient(UrlConnectionHttpClient.create()).build(),
                        config.auditBucket()),
                config.functionName(),
                Clock.systemUTC(),
                System.out);
    }

    LedgerAuditHandler(LedgerAuditor auditor, ConnectionFactory connections, ReportStore reports,
                       String functionName, Clock clock, PrintStream stdout) {
        this.auditor = auditor;
        this.connections = connections;
        this.reports = reports;
        this.functionName = functionName;
        this.clock = clock;
        this.stdout = stdout;
    }

    @Override
    public void handleRequest(InputStream input, OutputStream output, Context context) throws IOException {
        AuditReport report = audit(context);
        json.writeValue(output, report);
    }

    AuditReport audit(Context context) {
        AuditReport report;
        try {
            report = auditor.run(connections);
        } catch (SQLException e) {
            throw new IllegalStateException("ledger audit could not run: " + e.getMessage(), e);
        }
        String body = toJson(report);
        reports.put(report.s3Key(), body);

        stdout.println(EmfMetrics.auditFailures(json, clock.millis(), functionName, report.failures()));
        context.getLogger().log("ledger audit " + (report.passed() ? "PASSED" : "FAILED")
                + " failures=" + report.failures() + " report=" + report.s3Key() + " " + body);
        return report;
    }

    private String toJson(AuditReport report) {
        try {
            return json.writerWithDefaultPrettyPrinter().writeValueAsString(report);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
