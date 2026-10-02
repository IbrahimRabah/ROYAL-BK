package com.velora.api.catalog.domain;

/**
 * HOW a product is sold — not what it is. A watch, wallet or perfume is a
 * {@link Category}; this says whether it ships from stock, is made after the order,
 * or is bespoke work.
 *
 * <p>Only {@link #READY_MADE} products can be added to a cart.
 */
public enum FulfillmentType {
    READY_MADE,
    MADE_TO_ORDER,
    CUSTOM_WORK
}
