package com.velora.api.seo.dto;

/** A published FAQ in the language asked for (Arabic when the English text is missing). */
public record FaqResponse(Long id, String question, String answer, String topic) {
}
