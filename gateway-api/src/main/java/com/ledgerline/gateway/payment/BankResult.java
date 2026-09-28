package com.ledgerline.gateway.payment;

/** A definite answer from the bank. "No answer" is a {@link BankUnavailableException}, never a BankResult. */
public record BankResult(boolean approved, String declineReason, String bankReference) {

    /** The bank has no record of the charge, so it never took the money. */
    static final BankResult NOT_RECEIVED = new BankResult(false, "not_received_by_bank", null);

    public static BankResult approved(String bankReference) {
        return new BankResult(true, null, bankReference);
    }

    public static BankResult declined(String declineReason, String bankReference) {
        return new BankResult(false, declineReason, bankReference);
    }
}
