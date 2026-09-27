package com.ledgerline.gateway.ledger;

import static com.ledgerline.gateway.ledger.PostingRequest.credit;
import static com.ledgerline.gateway.ledger.PostingRequest.debit;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class LedgerServiceTest {

    // Merchant account id deliberately sits between the platform ids, to check lock ordering.
    private static final long CUSTOMER_FUNDS = 1;
    private static final long MERCHANT_PAYABLE = 5;
    private static final long PLATFORM_FEES = 9;
    private static final long MERCHANT_ID = 42;

    private final UUID paymentId = UUID.randomUUID();

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private LedgerRepository ledgerRepository;

    @InjectMocks
    private LedgerService ledgerService;

    @BeforeEach
    void setUp() {
        lenient().when(accountRepository.findByOwnerTypeAndOwnerIdIsNullAndType(OwnerType.PLATFORM, AccountType.CUSTOMER_FUNDS))
                .thenReturn(Optional.of(accountWithId(CUSTOMER_FUNDS)));
        lenient().when(accountRepository.findByOwnerTypeAndOwnerIdIsNullAndType(OwnerType.PLATFORM, AccountType.PLATFORM_FEES))
                .thenReturn(Optional.of(accountWithId(PLATFORM_FEES)));
        lenient().when(accountRepository.findByOwnerTypeAndOwnerIdAndType(OwnerType.MERCHANT, MERCHANT_ID, AccountType.MERCHANT_PAYABLE))
                .thenReturn(Optional.of(accountWithId(MERCHANT_PAYABLE)));
        // By default every requested account exists and gets locked.
        lenient().when(ledgerRepository.lockAccounts(anyList())).thenAnswer(call -> call.getArgument(0));
        lenient().when(ledgerRepository.insertEntry(any())).thenReturn(100L);
    }

    @Test
    void captureDebitsCustomerFundsAndSplitsBetweenMerchantAndFees() {
        long entryId = ledgerService.capture(paymentId, MERCHANT_ID, 10_000);

        assertThat(entryId).isEqualTo(100L);
        assertThat(postedEntry().postings()).containsExactly(
                debit(CUSTOMER_FUNDS, 10_000),
                credit(MERCHANT_PAYABLE, 9_800),
                credit(PLATFORM_FEES, 200));
        assertThat(postedEntry().type()).isEqualTo(EntryType.CAPTURE);
        assertThat(postedEntry().paymentId()).isEqualTo(paymentId);
    }

    @Test
    void captureOfOnePaisaHasNoFeePosting() {
        ledgerService.capture(paymentId, MERCHANT_ID, 1);

        assertThat(postedEntry().postings()).containsExactly(
                debit(CUSTOMER_FUNDS, 1),
                credit(MERCHANT_PAYABLE, 1));
        verify(ledgerRepository, never()).addToBalance(eq(PLATFORM_FEES), anyLong());
    }

    @Test
    void captureOf49PaiseHasNoFeePosting() {
        ledgerService.capture(paymentId, MERCHANT_ID, 49);

        assertThat(postedEntry().postings()).containsExactly(
                debit(CUSTOMER_FUNDS, 49),
                credit(MERCHANT_PAYABLE, 49));
    }

    @Test
    void captureOf50PaiseChargesOnePaisaFee() {
        ledgerService.capture(paymentId, MERCHANT_ID, 50);

        assertThat(postedEntry().postings()).containsExactly(
                debit(CUSTOMER_FUNDS, 50),
                credit(MERCHANT_PAYABLE, 49),
                credit(PLATFORM_FEES, 1));
    }

    @Test
    void captureOfVeryLargeAmountStaysBalancedWithoutOverflow() {
        long amount = Long.MAX_VALUE;
        long fee = Fees.captureFee(amount);

        ledgerService.capture(paymentId, MERCHANT_ID, amount);

        assertThat(postedEntry().postings()).containsExactly(
                debit(CUSTOMER_FUNDS, amount),
                credit(MERCHANT_PAYABLE, amount - fee),
                credit(PLATFORM_FEES, fee));
        verify(ledgerRepository).addToBalance(CUSTOMER_FUNDS, -amount);
        verify(ledgerRepository).addToBalance(MERCHANT_PAYABLE, amount - fee);
        verify(ledgerRepository).addToBalance(PLATFORM_FEES, fee);
    }

    @Test
    void postLocksAccountsInIdOrderBeforeWritingAnything() {
        ledgerService.capture(paymentId, MERCHANT_ID, 10_000);

        InOrder order = inOrder(ledgerRepository);
        order.verify(ledgerRepository).lockAccounts(List.of(CUSTOMER_FUNDS, MERCHANT_PAYABLE, PLATFORM_FEES));
        order.verify(ledgerRepository).insertEntry(any());
        order.verify(ledgerRepository).insertPosting(100L, debit(CUSTOMER_FUNDS, 10_000));
        order.verify(ledgerRepository).insertPosting(100L, credit(MERCHANT_PAYABLE, 9_800));
        order.verify(ledgerRepository).insertPosting(100L, credit(PLATFORM_FEES, 200));
        order.verify(ledgerRepository).addToBalance(CUSTOMER_FUNDS, -10_000);
        order.verify(ledgerRepository).addToBalance(MERCHANT_PAYABLE, 9_800);
        order.verify(ledgerRepository).addToBalance(PLATFORM_FEES, 200);
    }

    @Test
    void postNetsSeveralPostingsOnTheSameAccount() {
        JournalEntryRequest entry = new JournalEntryRequest(paymentId, EntryType.CAPTURE, null, List.of(
                debit(CUSTOMER_FUNDS, 300),
                credit(MERCHANT_PAYABLE, 100),
                credit(MERCHANT_PAYABLE, 200)));

        ledgerService.post(entry);

        verify(ledgerRepository).lockAccounts(List.of(CUSTOMER_FUNDS, MERCHANT_PAYABLE));
        verify(ledgerRepository).addToBalance(MERCHANT_PAYABLE, 300);
    }

    @Test
    void postFailsBeforeWritingWhenAnAccountDoesNotExist() {
        when(ledgerRepository.lockAccounts(anyList())).thenReturn(List.of(CUSTOMER_FUNDS));

        assertThatThrownBy(() -> ledgerService.capture(paymentId, MERCHANT_ID, 10_000))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown account");
        verify(ledgerRepository, never()).insertEntry(any());
    }

    @Test
    void captureFailsForMerchantWithoutPayableAccount() {
        assertThatThrownBy(() -> ledgerService.capture(paymentId, 7, 10_000))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("merchant 7");
        verifyNoInteractions(ledgerRepository);
    }

    @Test
    void unbalancedEntryIsRejectedBeforeReachingTheDatabase() {
        assertThatThrownBy(() -> new JournalEntryRequest(paymentId, EntryType.CAPTURE, null, List.of(
                debit(CUSTOMER_FUNDS, 100),
                credit(MERCHANT_PAYABLE, 99))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unbalanced");
    }

    @Test
    void nonPositivePostingIsRejected() {
        assertThatThrownBy(() -> credit(MERCHANT_PAYABLE, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> debit(CUSTOMER_FUNDS, -5)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void verifyReportsConsistentLedger() {
        when(ledgerRepository.findUnbalancedEntryIds()).thenReturn(List.of());
        when(ledgerRepository.findAccountIdsWithBalanceMismatch()).thenReturn(List.of());
        when(ledgerRepository.sumOfAllBalances()).thenReturn(0L);

        assertThat(ledgerService.verify())
                .isEqualTo(new LedgerVerification(true, true, List.of(), true, List.of(), 0));
    }

    @Test
    void verifyReportsMismatchedBalances() {
        when(ledgerRepository.findUnbalancedEntryIds()).thenReturn(List.of());
        when(ledgerRepository.findAccountIdsWithBalanceMismatch()).thenReturn(List.of(MERCHANT_PAYABLE));
        when(ledgerRepository.sumOfAllBalances()).thenReturn(1L);

        assertThat(ledgerService.verify())
                .isEqualTo(new LedgerVerification(false, true, List.of(), false, List.of(MERCHANT_PAYABLE), 1));
    }

    private JournalEntryRequest postedEntry() {
        ArgumentCaptor<JournalEntryRequest> captor = ArgumentCaptor.forClass(JournalEntryRequest.class);
        verify(ledgerRepository).insertEntry(captor.capture());
        return captor.getValue();
    }

    private static Account accountWithId(long id) {
        Account account = Account.merchantPayable(0, "INR"); // only the id matters to the service
        ReflectionTestUtils.setField(account, "id", id);
        return account;
    }
}
