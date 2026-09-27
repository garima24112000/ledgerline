package com.ledgerline.gateway.ledger;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "Result of auditing the whole ledger")
public record LedgerVerification(
        @Schema(description = "True if every journal entry and cached balance checks out and the global net is 0")
        boolean consistent,
        @Schema(description = "True if debits equal credits in every journal entry")
        boolean allEntriesBalanced,
        @Schema(description = "Ids of journal entries whose debits differ from their credits")
        List<Long> unbalancedEntryIds,
        @Schema(description = "True if every account's cached balance equals credits minus debits of its postings")
        boolean balancesMatchPostings,
        @Schema(description = "Ids of accounts whose cached balance differs from their postings")
        List<Long> mismatchedAccountIds,
        @Schema(description = "Sum of all cached account balances, in minor units. Must be 0", example = "0")
        long globalNet) {

    static LedgerVerification of(List<Long> unbalancedEntryIds, List<Long> mismatchedAccountIds, long globalNet) {
        boolean entriesBalanced = unbalancedEntryIds.isEmpty();
        boolean balancesMatch = mismatchedAccountIds.isEmpty();
        return new LedgerVerification(
                entriesBalanced && balancesMatch && globalNet == 0,
                entriesBalanced, unbalancedEntryIds,
                balancesMatch, mismatchedAccountIds,
                globalNet);
    }
}
