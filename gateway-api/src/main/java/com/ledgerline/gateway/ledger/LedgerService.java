package com.ledgerline.gateway.ledger;

import static com.ledgerline.gateway.ledger.PostingRequest.credit;
import static com.ledgerline.gateway.ledger.PostingRequest.debit;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LedgerService {

    private final AccountRepository accountRepository;
    private final LedgerRepository ledgerRepository;

    public LedgerService(AccountRepository accountRepository, LedgerRepository ledgerRepository) {
        this.accountRepository = accountRepository;
        this.ledgerRepository = ledgerRepository;
    }

    /**
     * Records a capture of {@code amount} with a 2% platform fee (rounded down):
     * debit CUSTOMER_FUNDS amount, credit MERCHANT_PAYABLE amount - fee, credit PLATFORM_FEES fee.
     * When the fee rounds down to 0 (amounts below 50) the fee posting is left out, since
     * postings must be positive.
     *
     * @return the id of the new journal entry
     */
    @Transactional
    public long capture(UUID paymentId, long merchantId, long amount) {
        long fee = Fees.captureFee(amount);

        long customerFunds = platformAccountId(AccountType.CUSTOMER_FUNDS);
        long platformFees = platformAccountId(AccountType.PLATFORM_FEES);
        long merchantPayable = accountRepository
                .findByOwnerTypeAndOwnerIdAndType(OwnerType.MERCHANT, merchantId, AccountType.MERCHANT_PAYABLE)
                .orElseThrow(() -> new IllegalArgumentException("merchant " + merchantId + " has no payable account"))
                .getId();

        List<PostingRequest> postings = new ArrayList<>();
        postings.add(debit(customerFunds, amount));
        postings.add(credit(merchantPayable, amount - fee));
        if (fee > 0) {
            postings.add(credit(platformFees, fee));
        }
        return post(new JournalEntryRequest(paymentId, EntryType.CAPTURE, "Capture of payment " + paymentId, postings));
    }

    /**
     * Writes a journal entry and updates the cached balances, all in one transaction:
     * <ol>
     *   <li>lock every affected account row, in ascending id order (avoids deadlocks);</li>
     *   <li>insert the entry and its postings;</li>
     *   <li>apply each account's net change to its cached balance.</li>
     * </ol>
     * Postgres checks at commit that the entry balances, and rejects any balance that would go
     * negative on an account that doesn't allow it; either failure rolls everything back.
     *
     * @return the id of the new journal entry
     */
    @Transactional
    public long post(JournalEntryRequest entry) {
        // Net change per account. A TreeMap keeps the ids sorted, which gives the lock order.
        Map<Long, Long> deltaByAccount = new TreeMap<>();
        for (PostingRequest posting : entry.postings()) {
            deltaByAccount.merge(posting.accountId(), posting.balanceDelta(), Math::addExact);
        }
        List<Long> accountIds = List.copyOf(deltaByAccount.keySet());

        List<Long> locked = ledgerRepository.lockAccounts(accountIds);
        if (!locked.equals(accountIds)) {
            throw new IllegalArgumentException("unknown account(s) in " + accountIds + ", found " + locked);
        }

        long entryId = ledgerRepository.insertEntry(entry);
        for (PostingRequest posting : entry.postings()) {
            ledgerRepository.insertPosting(entryId, posting);
        }
        deltaByAccount.forEach((accountId, delta) -> {
            if (delta != 0) {
                ledgerRepository.addToBalance(accountId, delta);
            }
        });
        return entryId;
    }

    /**
     * Audits the whole ledger. REPEATABLE READ makes the three queries see one snapshot, so a
     * capture committing between them can't produce a false alarm.
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public LedgerVerification verify() {
        return LedgerVerification.of(
                ledgerRepository.findUnbalancedEntryIds(),
                ledgerRepository.findAccountIdsWithBalanceMismatch(),
                ledgerRepository.sumOfAllBalances());
    }

    private long platformAccountId(AccountType type) {
        return accountRepository.findByOwnerTypeAndOwnerIdIsNullAndType(OwnerType.PLATFORM, type)
                .orElseThrow(() -> new IllegalStateException("platform account " + type + " is missing"))
                .getId();
    }
}
