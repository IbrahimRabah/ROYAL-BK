package com.velora.api.marketing.domain;

public enum PromotionScope {
    /** Every product on sale. */
    ALL,
    /** One category and everything under it. */
    CATEGORY,
    /** One product, every variant of it. */
    PRODUCT
}
