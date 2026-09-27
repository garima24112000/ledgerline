package com.ledgerline.gateway.ledger;

import java.util.List;
import java.util.UUID;

/**
 * A balanced set of postings to write as one journal entry.
 *
 * <p>The balance check here gives callers a clear error early. Postgres enforces the same
 * rule again at commit, so a bug here (or a raw SQL insert) still can't unbalance the ledger.
 */
public record JournalEntryRequest(UUID paymentId, EntryType type, String description, List<PostingRequest> postings) {

    public JournalEntryRequest {
        if (paymentId == null || type == null) {
            throw new IllegalArgumentException("paymentId and type are required");
        }
        if (postings == null || postings.size() < 2) {
            throw new IllegalArgumentException("a journal entry needs at least two postings");
        }
        postings = List.copyOf(postings);

        long net = 0;
        for (PostingRequest posting : postings) {
            net = Math.addExact(net, posting.balanceDelta());
        }
        if (net != 0) {
            throw new IllegalArgumentException("journal entry is unbalanced: credits - debits = " + net);
        }
    }
}
