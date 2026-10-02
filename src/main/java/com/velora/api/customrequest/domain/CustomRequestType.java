package com.velora.api.customrequest.domain;

/** What the customer is asking for. */
public enum CustomRequestType {

    /** A different size of a product we already sell. Needs a product and dimensions. */
    SIZE_VARIANT,

    /** A product we would make after the order. The product is optional. */
    MADE_TO_ORDER,

    /** Fully custom work. The product is optional. */
    CUSTOM_WORK
}
