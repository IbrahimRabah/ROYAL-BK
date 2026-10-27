package com.velora.api.audit.domain;

/**
 * What was done. Deliberately coarse.
 *
 * <p>An audit log is read months later by someone asking a specific question — "who
 * dropped that price?", "where did those six units go?". A hundred fine-grained
 * action types make that harder, not easier.
 */
public enum AuditAction {

    /** A price or cost changed. The most disputed field in any catalog. */
    PRICE_CHANGED,

    /** Stock moved by hand rather than through an order. */
    STOCK_ADJUSTED,

    STOCK_RECEIVED,

    /** A product went on or off sale. */
    PRODUCT_PUBLISHED,
    PRODUCT_ARCHIVED,

    /**
     * A shipping price, zone cap, or zone-wide terms (COD fee, delivery days) changed —
     * affects what every future customer pays.
     */
    SHIPPING_RATE_CHANGED,

    /**
     * A governorate was opened for delivery, closed, or moved to another zone. Changes
     * which orders the company can accept at all. oldValue / newValue are zone codes, or
     * CLOSED for a governorate that is in no zone.
     */
    GOVERNORATE_SERVICE_CHANGED,

    /**
     * A custom request moved to another status. oldValue / newValue are the statuses and
     * the reason is the staff note. Who contacted, accepted or rejected a customer.
     */
    CUSTOM_REQUEST_STATUS_CHANGED,

    /**
     * A custom request was quoted or re-quoted — who told the customer what price.
     * oldValue / newValue are the amounts; the request row only keeps the latest.
     */
    CUSTOM_REQUEST_QUOTED,

    /**
     * THE CUSTOMER accepted their own quote, from their account. Not a staff action: the actor is
     * the customer's user id and name. (A phone acceptance recorded by staff is
     * CUSTOM_REQUEST_STATUS_CHANGED.) oldValue / newValue are the statuses.
     */
    CUSTOM_REQUEST_ACCEPTED_BY_CUSTOMER,

    /** THE CUSTOMER rejected their own quote, from their account. See ..._ACCEPTED_BY_CUSTOMER. */
    CUSTOM_REQUEST_REJECTED_BY_CUSTOMER,

    /**
     * A delivery appointment was set or moved. It is a commitment made to the customer, and
     * the order only keeps the latest one: oldValue / newValue are the appointments
     * (ISO-8601, empty for the first) and the reason is the staff note.
     */
    ORDER_DELIVERY_SCHEDULED,

    /**
     * A portfolio item changed state: published, hidden, archived or restored. oldValue /
     * newValue are DRAFT, LIVE or ARCHIVED. A call that changes nothing records nothing.
     */
    PORTFOLIO_STATUS_CHANGED,

    /** An invoice was voided. Always needs a reason. */
    INVOICE_CANCELLED,

    /**
     * A new invoice was issued for an order whose earlier invoice had been cancelled.
     * oldValue is the cancelled invoice number, newValue the replacement.
     */
    INVOICE_REISSUED,

    /** A refund or a manual payment status change. */
    PAYMENT_ADJUSTED,

    /** Roles granted or revoked. */
    PERMISSION_CHANGED,

    /** Seller legal details changed — these end up printed on invoices. */
    STORE_PROFILE_CHANGED;

    /**
     * Whether a CUSTOMER, not a member of staff, performed this. Every other action is staff or
     * the system. The log shows an actor's name, which cannot tell the two apart, so a screen that
     * renders "X accepted the quote" must read this (see AuditLogResponse.actorKind) instead of
     * assuming the name belongs to staff.
     */
    public boolean performedByCustomer() {
        return this == CUSTOM_REQUEST_ACCEPTED_BY_CUSTOMER
                || this == CUSTOM_REQUEST_REJECTED_BY_CUSTOMER;
    }
}
