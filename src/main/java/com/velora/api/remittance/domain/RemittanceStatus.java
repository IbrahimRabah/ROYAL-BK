package com.velora.api.remittance.domain;

public enum RemittanceStatus {

    /** Recorded and reconciled. The orders it covers are marked paid. */
    SETTLED,

    /**
     * The courier paid less than the orders total.
     *
     * <p>Common and not always a problem — a parcel may have been returned after the
     * batch was cut. But it needs a person to look, so it gets its own state rather
     * than a note nobody reads.
     */
    SHORT,

    /** Voided. Kept for the record; the orders revert to unpaid. */
    CANCELLED;

    /** Cancelled is final: nothing leaves it, and a cancelled settlement is never reopened. */
    public boolean isTerminal() {
        return this == CANCELLED;
    }

    /** The only legal move out of SETTLED or SHORT. */
    public boolean canBeCancelled() {
        return !isTerminal();
    }
}
