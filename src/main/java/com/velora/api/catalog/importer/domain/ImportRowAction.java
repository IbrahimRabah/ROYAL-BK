package com.velora.api.catalog.importer.domain;

/** What committing the row does: a new SKU creates a product, a known SKU updates its product. */
public enum ImportRowAction {
    CREATE,
    UPDATE
}
