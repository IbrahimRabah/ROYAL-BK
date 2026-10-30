package com.velora.api.catalog.importer.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * One sheet row. {@code data} is the parsed row and {@code messages} what the checks said about
 * it, both JSON: they are only ever read back whole, never queried.
 */
@Entity
@Table(name = "import_batch_row")
@Getter
@Setter
@NoArgsConstructor
public class ImportBatchRow {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "batch_id", nullable = false)
    private Long batchId;

    /** The row number in the sheet, where the header row is 1. */
    @Column(name = "row_no", nullable = false)
    private int rowNo;

    @Column(name = "sku", length = 60)
    private String sku;

    @Enumerated(EnumType.STRING)
    @Column(name = "action", length = 10)
    private ImportRowAction action;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 10)
    private ImportRowStatus status;

    @Column(name = "data", nullable = false)
    private String data;

    @Column(name = "messages")
    private String messages;

    @Column(name = "product_id")
    private Long productId;
}
