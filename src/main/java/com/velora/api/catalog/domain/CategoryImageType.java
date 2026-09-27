package com.velora.api.catalog.domain;

/** The two image slots a category has — one of each, never a gallery. */
public enum CategoryImageType {
    /** Card image shown on the homepage and category listings. */
    CARD,
    /** Header image on the category landing page. */
    BANNER
}
