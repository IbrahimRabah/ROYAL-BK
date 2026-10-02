package com.velora.api.catalog.domain;

/** Parcel size bucket used for shipping. Required on {@link FulfillmentType#READY_MADE} products. */
public enum ShippingSizeClass {
    SMALL,
    MEDIUM,
    LARGE
}
