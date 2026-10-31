package com.velora.api.catalog.imageimport.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

@Schema(description = "The names of the files the person picked. No bytes are sent at this step")
public record ImageMatchRequest(
        @NotNull ImageImportMode mode,
        @NotEmpty @Size(max = 5000) List<@Valid @NotNull FileRef> files
) {

    public record FileRef(
            @Schema(description = "The file name, e.g. CH101.jpg (a path is cut down to its last part)")
            @NotBlank @Size(max = 500) String name,
            @Schema(description = "Size on disk in bytes, shown back in the report") long sizeBytes,
            @Schema(description = "The folder it was in, shown back so two files with one name can be told apart")
            @Size(max = 500) String folder
    ) {
    }
}
