package com.velora.api.catalog.review.domain;

/** A review is public only once staff approve it. Rejecting an approved review takes it down again. */
public enum ReviewStatus {
    PENDING,
    APPROVED,
    REJECTED
}
