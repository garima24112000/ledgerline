package com.ledgerline.audit;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/**
 * The four ledger checks, as plain SQL. The first three are the same queries as gateway-api's
 * LedgerRepository (/admin/ledger/verify); they are copied rather than shared, because depending on
 * gateway-api would pull Spring into the Lambda. LedgerAuditorIT runs them against gateway-api's real
 * Flyway migrations, so schema drift fails the build.
 *
 * The three ledger checks are full scans by design: they audit everything. The UNKNOWN check uses
 * the partial index payments_unresolved_idx.
 */
public final class LedgerAuditor {

    /** At most this many offending ids per check go into the report; the count is always exact. */
    static final int MAX_EXAMPLES = 100;

    // Entries whose debits and credits differ, or that have no postings at all.
    // The commit-time trigger should keep this empty forever.
    private static final String UNBALANCED_ENTRIES = """
            SELECT id, COUNT(*) OVER () AS total
            FROM (
                SELECT e.id
                FROM journal_entries e
                LEFT JOIN postings p ON p.journal_entry_id = e.id
                GROUP BY e.id
                HAVING COUNT(p.id) = 0
                    OR SUM(CASE WHEN p.direction = 'CREDIT' THEN p.amount ELSE -p.amount END) <> 0
            ) unbalanced
            ORDER BY id
            LIMIT ?""";

    // Accounts whose cached balance differs from credits - debits over their postings.
    private static final String BALANCE_MISMATCHES = """
            SELECT a.id, COUNT(*) OVER () AS total
            FROM accounts a
            LEFT JOIN (
                SELECT account_id,
                       SUM(CASE WHEN direction = 'CREDIT' THEN amount ELSE -amount END) AS net
                FROM postings
                GROUP BY account_id
            ) p ON p.account_id = a.id
            WHERE a.balance <> COALESCE(p.net, 0)
            ORDER BY a.id
            LIMIT ?""";

    // Per currency: summing INR and USD balances together would be meaningless.
    private static final String NONZERO_NET = """
            SELECT currency, SUM(balance) AS net
            FROM accounts
            GROUP BY currency
            HAVING SUM(balance) <> 0
            ORDER BY currency""";

    // updated_at = when the payment became UNKNOWN (its last change), i.e. how long it has been unresolved.
    // Matches payments_unresolved_idx (updated_at) WHERE status IN ('PENDING', 'UNKNOWN').
    private static final String STALE_UNKNOWN = """
            SELECT id, COUNT(*) OVER () AS total
            FROM payments
            WHERE status = 'UNKNOWN'
              AND updated_at < now() - make_interval(mins => ?)
            ORDER BY updated_at
            LIMIT ?""";

    private final Duration unknownMaxAge;
    private final ZoneId zone;
    private final Clock clock;

    public LedgerAuditor(Duration unknownMaxAge, ZoneId zone, Clock clock) {
        this.unknownMaxAge = unknownMaxAge;
        this.zone = zone;
        this.clock = clock;
    }

    /**
     * Production entry point: runs {@link #audit} in its own read-only REPEATABLE READ transaction,
     * so all four checks see one snapshot. Under READ COMMITTED a payment committed between two
     * checks could make them disagree and report a mismatch that never existed.
     */
    public AuditReport run(ConnectionFactory connections) throws SQLException {
        try (Connection connection = connections.open()) {
            connection.setAutoCommit(false);
            connection.setReadOnly(true);
            connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            try {
                AuditReport report = audit(connection);
                connection.commit();
                return report;
            } catch (SQLException | RuntimeException e) {
                connection.rollback();
                throw e;
            }
        }
    }

    /**
     * Runs the checks on the given connection, inside whatever transaction the caller has open.
     * It never commits, rolls back or changes connection settings, so a test can call it in the
     * same transaction as rows it inserted but has not committed (another connection couldn't see them).
     */
    public AuditReport audit(Connection connection) throws SQLException {
        Instant started = clock.instant();
        List<CheckResult> checks = List.of(
                idCheck(connection, "entries_balanced",
                        "Every journal entry has postings and its debits equal its credits",
                        UNBALANCED_ENTRIES),
                idCheck(connection, "balances_match_postings",
                        "Every account's cached balance equals credits minus debits of its postings",
                        BALANCE_MISMATCHES),
                globalNet(connection),
                idCheck(connection, "no_stale_unknown_payments",
                        "No payment has been UNKNOWN for more than " + unknownMaxAge.toMinutes() + " minutes",
                        STALE_UNKNOWN, Math.toIntExact(unknownMaxAge.toMinutes())));
        long durationMs = Duration.between(started, clock.instant()).toMillis();
        return AuditReport.of(LocalDate.ofInstant(started, zone).toString(), started.toString(), durationMs, checks);
    }

    /** Runs a query returning (id, total) rows; the last parameter is always the LIMIT. */
    private static CheckResult idCheck(Connection connection, String name, String description, String sql,
                                       Object... params) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            int index = 1;
            for (Object param : params) {
                statement.setObject(index++, param);
            }
            statement.setInt(index, MAX_EXAMPLES);
            List<String> ids = new ArrayList<>();
            long total = 0;
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    ids.add(rows.getString("id"));
                    total = rows.getLong("total");
                }
            }
            return CheckResult.of(name, description, total, ids);
        }
    }

    private static CheckResult globalNet(Connection connection) throws SQLException {
        List<String> nonZero = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(NONZERO_NET);
             ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                nonZero.add(rows.getString("currency") + "=" + rows.getBigDecimal("net").toPlainString());
            }
        }
        return CheckResult.of("global_net_zero",
                "The sum of all account balances is 0 in every currency",
                nonZero.size(), nonZero);
    }
}
