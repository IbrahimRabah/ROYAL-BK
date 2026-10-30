package com.velora.api.catalog.importer.domain;

import com.velora.api.common.audit.BaseAuditEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * One uploaded product sheet. It is parsed and validated on upload and kept here so staff can
 * review every row; the catalogue is only touched when the batch is committed.
 */
@Entity
@Table(name = "import_batch")
@Getter
@Setter
@NoArgsConstructor
public class ImportBatch extends BaseAuditEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "file_name", nullable = false, length = 255)
    private String fileName;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private ImportBatchStatus status = ImportBatchStatus.PREVIEW;

    @Column(name = "total_rows", nullable = false)
    private int totalRows;

    @Column(name = "create_rows", nullable = false)
    private int createRows;

    @Column(name = "update_rows", nullable = false)
    private int updateRows;

    @Column(name = "error_rows", nullable = false)
    private int errorRows;

    @Column(name = "warning_rows", nullable = false)
    private int warningRows;

    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "committed_at")
    private OffsetDateTime committedAt;

    /** A batch with no error rows can be submitted. */
    public boolean isSubmittable() {
        return status == ImportBatchStatus.PREVIEW && errorRows == 0 && totalRows > 0;
    }
}
