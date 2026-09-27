package com.ledgerline.gateway.ledger;

import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Plain SQL for the parts of the ledger where locking and exact statements matter.
 * Every method must run inside the caller's transaction.
 */
@Repository
public class LedgerRepository {

    private final JdbcClient jdbc;

    public LedgerRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Locks the given accounts until the transaction ends. The ORDER BY sits below the row
     * lock in the plan, so rows are locked in id order, and two transactions touching the same
     * accounts can't each hold one lock while waiting for the other (no deadlock).
     *
     * @return the ids that were found and locked, in ascending order
     */
    public List<Long> lockAccounts(List<Long> accountIds) {
        return jdbc.sql("SELECT id FROM accounts WHERE id IN (:ids) ORDER BY id FOR UPDATE")
                .param("ids", accountIds)
                .query(Long.class)
                .list();
    }

    public long insertEntry(JournalEntryRequest entry) {
        return jdbc.sql("""
                        INSERT INTO journal_entries (payment_id, type, description)
                        VALUES (:paymentId, :type, :description)
                        RETURNING id""")
                .param("paymentId", entry.paymentId())
                .param("type", entry.type().name())
                .param("description", entry.description())
                .query(Long.class)
                .single();
    }

    public void insertPosting(long entryId, PostingRequest posting) {
        jdbc.sql("""
                        INSERT INTO postings (journal_entry_id, account_id, direction, amount)
                        VALUES (:entryId, :accountId, :direction, :amount)""")
                .param("entryId", entryId)
                .param("accountId", posting.accountId())
                .param("direction", posting.direction().name())
                .param("amount", posting.amount())
                .update();
    }

    public void addToBalance(long accountId, long delta) {
        jdbc.sql("UPDATE accounts SET balance = balance + :delta, version = version + 1 WHERE id = :id")
                .param("delta", delta)
                .param("id", accountId)
                .update();
    }

    // --- Verification (full scans by design: they audit the whole ledger) ---

    /** Entries whose debits and credits differ. The commit trigger should make this always empty. */
    public List<Long> findUnbalancedEntryIds() {
        return jdbc.sql("""
                        SELECT journal_entry_id
                        FROM postings
                        GROUP BY journal_entry_id
                        HAVING SUM(CASE WHEN direction = 'CREDIT' THEN amount ELSE -amount END) <> 0
                        ORDER BY journal_entry_id""")
                .query(Long.class)
                .list();
    }

    /** Accounts whose cached balance differs from credits - debits over their postings. */
    public List<Long> findAccountIdsWithBalanceMismatch() {
        return jdbc.sql("""
                        SELECT a.id
                        FROM accounts a
                        LEFT JOIN (
                            SELECT account_id,
                                   SUM(CASE WHEN direction = 'CREDIT' THEN amount ELSE -amount END) AS net
                            FROM postings
                            GROUP BY account_id
                        ) p ON p.account_id = a.id
                        WHERE a.balance <> COALESCE(p.net, 0)
                        ORDER BY a.id""")
                .query(Long.class)
                .list();
    }

    /** Sum of every cached account balance. Must be 0 in a consistent ledger. */
    public long sumOfAllBalances() {
        return jdbc.sql("SELECT COALESCE(SUM(balance), 0) FROM accounts")
                .query(Long.class)
                .single();
    }
}
