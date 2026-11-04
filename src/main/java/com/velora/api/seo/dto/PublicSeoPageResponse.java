package com.velora.api.seo.dto;

/** What a static page puts in its head. Absent fields mean "use the built-in text". */
public record PublicSeoPageResponse(String title, String description, boolean noindex) {
}
