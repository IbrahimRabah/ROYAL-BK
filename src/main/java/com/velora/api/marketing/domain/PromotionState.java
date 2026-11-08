package com.velora.api.marketing.domain;

/** Status and clock together: only RUNNING changes a price. */
public enum PromotionState {
    DRAFT,
    PAUSED,
    SCHEDULED,
    RUNNING,
    ENDED
}
