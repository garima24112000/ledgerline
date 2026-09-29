package com.ledgerline.audit;

import java.util.List;

/**
 * Outcome of one audit check.
 *
 * @param name       stable identifier, e.g. "entries_balanced"
 * @param passed     true when no violation was found
 * @param violations how many rows violate the invariant (all of them, not only those listed)
 * @param examples   the first {@link LedgerAuditor#MAX_EXAMPLES} offending ids (or "INR=5" for the net check)
 */
public record CheckResult(String name, String description, boolean passed, long violations, List<String> examples) {

    static CheckResult of(String name, String description, long violations, List<String> examples) {
        return new CheckResult(name, description, violations == 0, violations, List.copyOf(examples));
    }
}
