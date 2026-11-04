package com.velora.api.seo.dto;

import com.velora.api.seo.domain.FaqStatus;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

public record FaqBulkStatusRequest(@NotEmpty @Size(max = 500) List<Long> ids, @NotNull FaqStatus status) {
}
