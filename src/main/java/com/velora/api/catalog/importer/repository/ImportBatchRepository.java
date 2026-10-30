package com.velora.api.catalog.importer.repository;

import com.velora.api.catalog.importer.domain.ImportBatch;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ImportBatchRepository extends JpaRepository<ImportBatch, Long> {
}
