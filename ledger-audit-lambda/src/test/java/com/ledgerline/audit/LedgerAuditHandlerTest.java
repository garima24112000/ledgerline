package com.ledgerline.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class LedgerAuditHandlerTest {

    private final ObjectMapper json = new ObjectMapper();
    private final LedgerAuditor auditor = mock(LedgerAuditor.class);
    private final ConnectionFactory connections = mock(ConnectionFactory.class);
    private final ReportStore reports = mock(ReportStore.class);
    private final Context context = mock(Context.class);
    private final ByteArrayOutputStream stdout = new ByteArrayOutputStream();
    private LedgerAuditHandler handler;

    @BeforeEach
    void setUp() {
        when(context.getLogger()).thenReturn(mock(LambdaLogger.class));
        Clock clock = Clock.fixed(Instant.parse("2026-09-28T20:30:00Z"), ZoneOffset.UTC);
        handler = new LedgerAuditHandler(auditor, connections, reports, "ledgerline-ledger-audit", clock,
                new PrintStream(stdout, true, StandardCharsets.UTF_8));
    }

    @Test
    void writesTheReportToS3UnderTheAuditDateAndEmitsTheFailureCount() throws Exception {
        AuditReport report = AuditReport.of("2026-09-29", "2026-09-28T20:30:00Z", 42, List.of(
                CheckResult.of("entries_balanced", "d", 0, List.of()),
                CheckResult.of("balances_match_postings", "d", 1, List.of("7")),
                CheckResult.of("global_net_zero", "d", 1, List.of("INR=1")),
                CheckResult.of("no_stale_unknown_payments", "d", 0, List.of())));
        when(auditor.run(connections)).thenReturn(report);

        ByteArrayOutputStream response = new ByteArrayOutputStream();
        handler.handleRequest(new ByteArrayInputStream("{}".getBytes(StandardCharsets.UTF_8)), response, context);

        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(reports).put(eq("audits/2026-09-29.json"), body.capture());
        JsonNode stored = json.readTree(body.getValue());
        assertThat(stored.get("passed").asBoolean()).isFalse();
        assertThat(stored.get("failures").asInt()).isEqualTo(2);
        assertThat(stored.get("checks").get(1).get("examples").get(0).asText()).isEqualTo("7");

        // The Lambda response is the same report.
        assertThat(json.readTree(response.toByteArray()).get("failures").asInt()).isEqualTo(2);

        // One EMF line with the metric value on stdout.
        JsonNode emf = json.readTree(stdout.toString(StandardCharsets.UTF_8).strip());
        assertThat(emf.get("LedgerAuditFailures").asInt()).isEqualTo(2);
        assertThat(emf.get("FunctionName").asText()).isEqualTo("ledgerline-ledger-audit");
    }

    @Test
    void aDatabaseErrorFailsTheInvocationAndWritesNoReport() throws Exception {
        when(auditor.run(connections)).thenThrow(new SQLException("connection attempt timed out"));

        assertThatThrownBy(() -> handler.handleRequest(
                new ByteArrayInputStream(new byte[0]), new ByteArrayOutputStream(), context))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ledger audit could not run");
        verify(reports, never()).put(anyString(), any());
        assertThat(stdout.size()).isZero(); // no metric: the Lambda Errors alarm covers this case
    }
}
