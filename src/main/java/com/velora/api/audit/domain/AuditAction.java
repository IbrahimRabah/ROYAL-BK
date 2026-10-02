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

    /** An invoice was voided. Always needs a reason. */
    INVOICE_CANCELLED,

    /** A refund or a manual payment status change. */
    PAYMENT_ADJUSTED,

    /** Roles granted or revoked. */
    PERMISSION_CHANGED,

    /** Seller legal details changed — these end up printed on invoices. */
    STORE_PROFILE_CHANGED
}
