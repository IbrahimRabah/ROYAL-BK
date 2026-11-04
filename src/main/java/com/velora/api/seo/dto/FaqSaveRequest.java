package com.velora.api.seo.dto;

import com.velora.api.seo.domain.FaqScope;
import com.velora.api.seo.domain.FaqStatus;
import com.velora.api.seo.domain.FaqTopic;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

@Schema(description = "Create or replace a FAQ. Plain text only")
public record FaqSaveRequest(
        @NotNull(message = "scope is required") FaqScope scope,
        @Schema(description = "The product or category id; required for PRODUCT and CATEGORY, absent for GENERAL")
        Long targetId,
        @Schema(description = "The group of a GENERAL FAQ; ignored for the other scopes") FaqTopic topic,
        @NotBlank(message = "The Arabic question is required") @Size(max = 500) String questionAr,
        @NotBlank(message = "The Arabic answer is required") @Size(max = 2000) String answerAr,
        @Size(max = 500) String questionEn,
        @Size(max = 2000) String answerEn,
        @Schema(description = "Order among its page's FAQs; the end of the list when absent") Integer position,
        @NotNull(message = "status is required") FaqStatus status
) {
}
