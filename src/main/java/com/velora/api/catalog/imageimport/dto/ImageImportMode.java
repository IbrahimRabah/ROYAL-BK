package com.velora.api.catalog.imageimport.dto;

/** What to do with a product that already has images. The operator must choose anything but SKIP. */
public enum ImageImportMode {
    /** Leave a product alone when it already has images (the default). */
    SKIP,
    /** Add the new images after the ones it has. */
    ADD,
    /** Remove the images it had before this run and put the new ones in their place. */
    REPLACE
}
