package com.ledgerline.audit;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * The audit SQL against gateway-api's real schema: its Flyway migrations are applied from
 * ../gateway-api/src/main/resources/db/migration, with every constraint and trigger intact.
 *
 * Each test gets its own database, copied from a migrated template (CREATE DATABASE ... TEMPLATE),
 * so committed test data never leaks between tests and Flyway runs only once.
 */
class LedgerAuditorIT {

    private static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");
    private static final String TEMPLATE = "ledger_template";
    private static final AtomicInteger databases = new AtomicInteger();

    // 2026-09-29T02:00Z is still 2026-09-28 in America/New_York (UTC-4 in September): the report uses the local date.
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-29T02:00:00Z"), ZoneOffset.UTC);

    private final LedgerAuditor auditor = new LedgerAuditor(Duration.ofMinutes(60), ZoneId.of("America/New_York"), CLOCK);
    private ConnectionFactory connections;

    @BeforeAll
    static void migrateTemplate() throws SQLException {
        postgres.start();
        admin("CREATE DATABASE " + TEMPLATE);
        Flyway.configure()
                .dataSource(urlFor(TEMPLATE), postgres.getUsername(), postgres.getPassword())
                .locations("filesystem:../gateway-api/src/main/resources/db/migration")
                .placeholders(Map.of("demo_webhook_url", "http://localhost:8083/webhooks"))
                .load()
                .migrate();
    }

    @AfterAll
    static void stop() {
        postgres.stop();
    }

    @BeforeEach
    void freshDatabase() throws SQLException {
        String name = "audit_test_" + databases.incrementAndGet();
        admin("CREATE DATABASE " + name + " TEMPLATE " + TEMPLATE);
        connections = () -> DriverManager.getConnection(urlFor(name), postgres.getUsername(), postgres.getPassword());
    }

    @Test
    void cleanLedgerPassesEveryCheck() throws SQLException {
        capture(10_000); // one real, balanced capture, like LedgerService posts it

        AuditReport report = auditor.run(connections);

        assertThat(report.passed()).isTrue();
        assertThat(report.failures()).isZero();
        assertThat(report.checks()).extracting(CheckResult::name).containsExactly(
                "entries_balanced", "balances_match_postings", "global_net_zero", "no_stale_unknown_payments");
        assertThat(report.checks()).allMatch(CheckResult::passed);
        assertThat(report.auditDate()).isEqualTo("2026-09-28");
        assertThat(report.s3Key()).isEqualTo("audits/2026-09-28.json");
    }

    @Test
    void reportsCachedBalanceThatDisagreesWithPostings() throws SQLException {
        capture(10_000);
        long merchantAccount = accountId("MERCHANT_PAYABLE");
        execute("UPDATE accounts SET balance = balance + 1 WHERE id = " + merchantAccount); // accounts aren't append-only

        AuditReport report = auditor.run(connections);

        assertThat(report.passed()).isFalse();
        assertThat(check(report, "balances_match_postings").examples()).containsExactly(String.valueOf(merchantAccount));
        // The cached balances now sum to 1 instead of 0 as well.
        assertThat(check(report, "global_net_zero").examples()).containsExactly("INR=1");
        assertThat(check(report, "entries_balanced").passed()).isTrue();
        assertThat(report.failures()).isEqualTo(2);
    }

    @Test
    void reportsPaymentsUnknownForLongerThanAnHourByUpdatedAt() throws SQLException {
        UUID stale = insertPayment("UNKNOWN", "61 minutes", "61 minutes"); // unresolved for 61 min
        insertPayment("UNKNOWN", "59 minutes", "59 minutes");              // unresolved for 59 min: fine
        // Created 3 hours ago but only became UNKNOWN 10 minutes ago: updated_at is what counts.
        insertPayment("UNKNOWN", "10 minutes", "3 hours");
        insertPayment("FAILED", "5 hours", "5 hours");                     // old, but resolved

        CheckResult check = check(auditor.run(connections), "no_stale_unknown_payments");

        assertThat(check.passed()).isFalse();
        assertThat(check.violations()).isEqualTo(1);
        assertThat(check.examples()).containsExactly(stale.toString());
    }

    /**
     * The deferred constraint triggers make an unbalanced entry impossible to COMMIT, so it can only
     * exist inside an open transaction. Another connection would never see it. So the test inserts it
     * and calls audit(connection) on the SAME connection before committing, then rolls back.
     */
    @Test
    void reportsUnbalancedAndEmptyEntriesSeenInsideTheSameOpenTransaction() throws SQLException {
        try (Connection connection = connections.open()) {
            connection.setAutoCommit(false);
            UUID payment = insertPayment(connection, "CAPTURED", "0 minutes", "0 minutes");
            long oneSided = insertEntry(connection, payment, "CAPTURE");
            insertPosting(connection, oneSided, accountId(connection, "CUSTOMER_FUNDS"), "DEBIT", 500);
            long empty = insertEntry(connection, payment, "REFUND");

            // journal_entries_balanced / postings_balanced are DEFERRABLE INITIALLY DEFERRED: they only
            // fire at COMMIT, so both rows are still visible here.
            AuditReport report = auditor.audit(connection);

            assertThat(check(report, "entries_balanced").examples())
                    .containsExactly(String.valueOf(oneSided), String.valueOf(empty));
            connection.rollback();
        }

        // Nothing was committed: a fresh audit of the same database is clean.
        assertThat(auditor.run(connections).passed()).isTrue();
    }

    // --- helpers -----------------------------------------------------------------------------------

    private static CheckResult check(AuditReport report, String name) {
        return report.checks().stream().filter(c -> c.name().equals(name)).findFirst().orElseThrow();
    }

    /** A balanced capture: customer funds -> merchant payable + platform fee, with cached balances updated. */
    private void capture(long amount) throws SQLException {
        try (Connection connection = connections.open()) {
            connection.setAutoCommit(false);
            UUID payment = insertPayment(connection, "CAPTURED", "0 minutes", "0 minutes");
            long entry = insertEntry(connection, payment, "CAPTURE");
            long fee = amount / 50;
            long customerFunds = accountId(connection, "CUSTOMER_FUNDS");
            long merchant = accountId(connection, "MERCHANT_PAYABLE");
            long fees = accountId(connection, "PLATFORM_FEES");
            insertPosting(connection, entry, customerFunds, "DEBIT", amount);
            insertPosting(connection, entry, merchant, "CREDIT", amount - fee);
            insertPosting(connection, entry, fees, "CREDIT", fee);
            try (Statement statement = connection.createStatement()) {
                statement.execute("UPDATE accounts SET balance = balance - " + amount + " WHERE id = " + customerFunds);
                statement.execute("UPDATE accounts SET balance = balance + " + (amount - fee) + " WHERE id = " + merchant);
                statement.execute("UPDATE accounts SET balance = balance + " + fee + " WHERE id = " + fees);
            }
            connection.commit();
        }
    }

    private UUID insertPayment(String status, String updatedAgo, String createdAgo) throws SQLException {
        try (Connection connection = connections.open()) {
            return insertPayment(connection, status, updatedAgo, createdAgo);
        }
    }

    private static UUID insertPayment(Connection connection, String status, String updatedAgo, String createdAgo)
            throws SQLException {
        UUID id = UUID.randomUUID();
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO payments (id, merchant_id, amount, currency, card_token, merchant_order_id, status,
                                      idempotency_key, created_at, updated_at)
                SELECT ?, id, 10000, 'INR', 'tok_test', ?, ?, ?, now() - ?::interval, now() - ?::interval
                FROM merchants WHERE name = 'Chai Point'""")) {
            statement.setObject(1, id);
            statement.setString(2, "order-" + id);
            statement.setString(3, status);
            statement.setString(4, "key-" + id);
            statement.setString(5, createdAgo);
            statement.setString(6, updatedAgo);
            statement.executeUpdate();
        }
        return id;
    }

    private static long insertEntry(Connection connection, UUID payment, String type) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO journal_entries (payment_id, type) VALUES (?, ?) RETURNING id")) {
            statement.setObject(1, payment);
            statement.setString(2, type);
            try (ResultSet rows = statement.executeQuery()) {
                rows.next();
                return rows.getLong(1);
            }
        }
    }

    private static void insertPosting(Connection connection, long entry, long account, String direction, long amount)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO postings (journal_entry_id, account_id, direction, amount) VALUES (?, ?, ?, ?)")) {
            statement.setLong(1, entry);
            statement.setLong(2, account);
            statement.setString(3, direction);
            statement.setLong(4, amount);
            statement.executeUpdate();
        }
    }

    private long accountId(String type) throws SQLException {
        try (Connection connection = connections.open()) {
            return accountId(connection, type);
        }
    }

    /** Platform accounts, or Chai Point's MERCHANT_PAYABLE account. */
    private static long accountId(Connection connection, String type) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT a.id FROM accounts a
                LEFT JOIN merchants m ON m.id = a.owner_id
                WHERE a.type = ? AND (a.owner_type = 'PLATFORM' OR m.name = 'Chai Point')""")) {
            statement.setString(1, type);
            try (ResultSet rows = statement.executeQuery()) {
                rows.next();
                return rows.getLong(1);
            }
        }
    }

    private void execute(String sql) throws SQLException {
        try (Connection connection = connections.open(); Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static void admin(String sql) throws SQLException {
        try (Connection connection = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static String urlFor(String database) {
        return "jdbc:postgresql://%s:%d/%s".formatted(
                postgres.getHost(), postgres.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT), database);
    }
}
