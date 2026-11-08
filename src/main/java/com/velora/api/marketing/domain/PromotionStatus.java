package com.velora.api.marketing.domain;

/** What staff decided. Whether it is running also depends on the clock: see {@link PromotionState}. */
public enum PromotionStatus {
    DRAFT,
    ACTIVE,
    PAUSED
}
