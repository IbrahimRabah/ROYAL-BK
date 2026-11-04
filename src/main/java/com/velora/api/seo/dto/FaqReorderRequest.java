package com.velora.api.seo.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;

public record FaqReorderRequest(@NotEmpty @Size(max = 500) List<Long> ids) {
}
