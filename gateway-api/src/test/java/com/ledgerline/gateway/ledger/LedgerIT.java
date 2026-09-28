package com.ledgerline.gateway.ledger;

import static com.ledgerline.gateway.ledger.PostingRequest.credit;
import static com.ledgerline.gateway.ledger.PostingRequest.debit;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ledgerline.gateway.AbstractGatewayIT;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * Runs against real Postgres so the triggers, constraints and row locks are the ones in production.
 * Tests never delete ledger rows (the triggers forbid it); each test creates its own merchant and
 * asserts on changes, so tests stay independent while sharing one database.
 */
class LedgerIT extends AbstractGatewayIT {

    @Autowired
    private LedgerService ledgerService;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private DataSource dataSource;

    private long merchantId;
    private long merchantPayable;
    private long customerFunds;
    private long platformFees;

    @BeforeEach
    void setUpMerchant() {
        TestMerchant merchant = createMerchant();
        merchantId = merchant.id();
        merchantPayable = merchant.payableAccountId();
        customerFunds = platformAccount(AccountType.CUSTOMER_FUNDS).getId();
        platformFees = platformAccount(AccountType.PLATFORM_FEES).getId();
    }

    @Test
    void balancedCaptureIsRecordedWithPostingsAndBalances() {
        long customerFundsBefore = balance(customerFunds);
        long feesBefore = balance(platformFees);
        UUID paymentId = insertPayment(merchantId);

        long entryId = ledgerService.capture(paymentId, merchantId, 10_000);

        List<PostingRequest> postings = jdbc.sql("""
                        SELECT account_id, direction, amount FROM postings
                        WHERE journal_entry_id = ? ORDER BY id""")
                .param(entryId)
                .query((rs, row) -> new PostingRequest(
                        rs.getLong("account_id"), Direction.valueOf(rs.getString("direction")), rs.getLong("amount")))
                .list();
        assertThat(postings).containsExactly(
                debit(customerFunds, 10_000),
                credit(merchantPayable, 9_800),
                credit(platformFees, 200));
        assertThat(jdbc.sql("SELECT payment_id FROM journal_entries WHERE id = ?").param(entryId).query(UUID.class).single())
                .isEqualTo(paymentId);

        assertThat(balance(merchantPayable)).isEqualTo(9_800);
        assertThat(balance(platformFees) - feesBefore).isEqualTo(200);
        assertThat(balance(customerFunds) - customerFundsBefore).isEqualTo(-10_000);
    }

    @Test
    void unbalancedEntryInsertedWithRawSqlIsRejectedAtCommit() throws SQLException {
        long entriesBefore = count("journal_entries");

        try (Connection connection = dataSource.getConnection(); Statement sql = connection.createStatement()) {
            connection.setAutoCommit(false);

            // Both inserts succeed: the balance check is deferred until commit.
            sql.execute("INSERT INTO journal_entries (payment_id, type) VALUES ('" + insertPayment(merchantId) + "', 'CAPTURE')");
            sql.execute("INSERT INTO postings (journal_entry_id, account_id, direction, amount) "
                    + "VALUES (currval('journal_entries_id_seq'), " + customerFunds + ", 'DEBIT', 100), "
                    + "       (currval('journal_entries_id_seq'), " + merchantPayable + ", 'CREDIT', 99)");

            assertThatThrownBy(connection::commit)
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("is unbalanced: debits 100 <> credits 99");
        }

        assertThat(count("journal_entries")).isEqualTo(entriesBefore);
    }

    @Test
    void journalEntryWithoutPostingsIsRejectedAtCommit() throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement sql = connection.createStatement()) {
            connection.setAutoCommit(false);
            sql.execute("INSERT INTO journal_entries (payment_id, type) VALUES ('" + insertPayment(merchantId) + "', 'CAPTURE')");

            assertThatThrownBy(connection::commit).hasMessageContaining("has no postings");
        }
    }

    @Test
    void postingsCannotBeUpdatedOrDeleted() {
        long entryId = ledgerService.capture(insertPayment(merchantId), merchantId, 10_000);

        assertThatThrownBy(() -> jdbc.sql("UPDATE postings SET amount = amount + 1 WHERE journal_entry_id = ?")
                .param(entryId).update())
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("postings is append-only: UPDATE is not allowed");
        assertThatThrownBy(() -> jdbc.sql("DELETE FROM postings WHERE journal_entry_id = ?").param(entryId).update())
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("postings is append-only: DELETE is not allowed");
        assertThatThrownBy(() -> jdbc.sql("TRUNCATE postings CASCADE").update())
                .hasMessageContaining("append-only: TRUNCATE is not allowed");

        assertThat(jdbc.sql("SELECT COUNT(*) FROM postings WHERE journal_entry_id = ?").param(entryId).query(Long.class).single())
                .isEqualTo(3);
    }

    @Test
    void journalEntriesCannotBeUpdatedOrDeleted() {
        long entryId = ledgerService.capture(insertPayment(merchantId), merchantId, 10_000);

        assertThatThrownBy(() -> jdbc.sql("UPDATE journal_entries SET description = 'edited' WHERE id = ?")
                .param(entryId).update())
                .hasMessageContaining("journal_entries is append-only: UPDATE is not allowed");
        assertThatThrownBy(() -> jdbc.sql("DELETE FROM journal_entries WHERE id = ?").param(entryId).update())
                .hasMessageContaining("journal_entries is append-only: DELETE is not allowed");
    }

    @Test
    void paymentCannotBeCapturedTwice() {
        UUID paymentId = insertPayment(merchantId);
        ledgerService.capture(paymentId, merchantId, 10_000);

        assertThatThrownBy(() -> ledgerService.capture(paymentId, merchantId, 10_000))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("journal_entries_one_capture_per_payment_uq");
        assertThat(balance(merchantPayable)).isEqualTo(9_800); // the second attempt rolled back fully
    }

    @Test
    void merchantPayableCannotGoNegative() {
        // A refund of money the merchant doesn't have: debit MERCHANT_PAYABLE, credit CUSTOMER_FUNDS.
        JournalEntryRequest overdraw = new JournalEntryRequest(insertPayment(merchantId), EntryType.REFUND, null, List.of(
                debit(merchantPayable, 1), credit(customerFunds, 1)));

        assertThatThrownBy(() -> ledgerService.post(overdraw))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("accounts_check");
        assertThat(balance(merchantPayable)).isZero();
    }

    @Test
    void concurrentCapturesOnTheSameMerchantProduceTheCorrectBalances() throws Exception {
        int captures = 50;
        long amount = 12_345; // fee 246, merchant gets 12,099
        long customerFundsBefore = balance(customerFunds);
        long feesBefore = balance(platformFees);

        CountDownLatch start = new CountDownLatch(1);
        List<Future<Long>> results = new ArrayList<>();
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < captures; i++) {
                UUID paymentId = insertPayment(merchantId);
                results.add(executor.submit(() -> {
                    start.await(); // release all threads at once to maximise contention
                    return ledgerService.capture(paymentId, merchantId, amount);
                }));
            }
            start.countDown();
            for (Future<Long> result : results) {
                result.get(); // rethrows any failure (e.g. a deadlock) from the worker
            }
        }

        long fee = Fees.captureFee(amount);
        assertThat(balance(merchantPayable)).isEqualTo(captures * (amount - fee));
        assertThat(balance(platformFees) - feesBefore).isEqualTo(captures * fee);
        assertThat(balance(customerFunds) - customerFundsBefore).isEqualTo(-captures * amount);
        assertThat(ledgerService.verify().consistent()).isTrue();
    }

    @Test
    void verifyEndpointReportsConsistentLedger() {
        ledgerService.capture(insertPayment(merchantId), merchantId, 5_000);

        LedgerVerification report = rest.withBasicAuth(ADMIN_USER, ADMIN_PASSWORD).getForObject("/admin/ledger/verify", LedgerVerification.class);

        assertThat(report).isEqualTo(new LedgerVerification(true, true, List.of(), true, List.of(), 0));
    }

    @Test
    void verifyEndpointDetectsCachedBalanceDrift() {
        ledgerService.capture(insertPayment(merchantId), merchantId, 5_000);
        // accounts is not append-only, so a buggy writer could corrupt the cache. Simulate that.
        jdbc.sql("UPDATE accounts SET balance = balance + 1 WHERE id = ?").param(merchantPayable).update();
        try {
            LedgerVerification report = rest.withBasicAuth(ADMIN_USER, ADMIN_PASSWORD).getForObject("/admin/ledger/verify", LedgerVerification.class);

            assertThat(report.consistent()).isFalse();
            assertThat(report.allEntriesBalanced()).isTrue();
            assertThat(report.mismatchedAccountIds()).containsExactly(merchantPayable);
            assertThat(report.globalNet()).isEqualTo(1);
        } finally {
            jdbc.sql("UPDATE accounts SET balance = balance - 1 WHERE id = ?").param(merchantPayable).update();
        }
    }

    private Account platformAccount(AccountType type) {
        return accountRepository.findByOwnerTypeAndOwnerIdIsNullAndType(OwnerType.PLATFORM, type).orElseThrow();
    }

    private long count(String table) {
        return jdbc.sql("SELECT COUNT(*) FROM " + table).query(Long.class).single();
    }
}
