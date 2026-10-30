package com.velora.api.catalog.importer.repository;

import com.velora.api.catalog.importer.domain.ImportBatchRow;
import com.velora.api.catalog.importer.domain.ImportRowStatus;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ImportBatchRowRepository extends JpaRepository<ImportBatchRow, Long> {

    /** The review order: rows with errors first (ERROR sorts before VALID), then in sheet order. */
    Page<ImportBatchRow> findByBatchIdOrderByStatusAscRowNoAsc(Long batchId, Pageable pageable);

    /** Rows of the batch holding this SKU (stored upper-case; the column collation is case-insensitive). */
    List<ImportBatchRow> findByBatchIdAndSku(Long batchId, String sku);

    Page<ImportBatchRow> findByBatchIdAndStatusOrderByRowNoAsc(Long batchId, ImportRowStatus status,
                                                               Pageable pageable);

    /** Every row of a batch, for the commit, which has to go through all of them. */
    List<ImportBatchRow> findByBatchIdOrderByRowNoAsc(Long batchId);
}
