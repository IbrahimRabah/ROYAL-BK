package com.velora.api.catalog.importer.domain;

/** Where an uploaded sheet is in its life: reviewed, then either submitted or thrown away. */
public enum ImportBatchStatus {
    PREVIEW,
    COMMITTED,
    DISCARDED
}
