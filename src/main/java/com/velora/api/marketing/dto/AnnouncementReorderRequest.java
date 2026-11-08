package com.velora.api.marketing.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;

public record AnnouncementReorderRequest(@NotEmpty @Size(max = 200) List<Long> ids) {
}
