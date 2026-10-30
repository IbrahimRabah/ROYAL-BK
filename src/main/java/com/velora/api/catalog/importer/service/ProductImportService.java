package com.velora.api.catalog.importer.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.velora.api.catalog.dto.admin.ProductAdminResponse;
import com.velora.api.catalog.dto.admin.ProductCreateRequest;
import com.velora.api.catalog.dto.admin.ProductCreateRequest.SpecificationRequest;
import com.velora.api.catalog.dto.admin.TranslationRequest;
import com.velora.api.catalog.dto.admin.VariantSaveRequest;
import com.velora.api.catalog.dto.admin.VariantSaveRequest.VariantItem;
import com.velora.api.catalog.importer.domain.ImportBatch;
import com.velora.api.catalog.importer.domain.ImportBatchRow;
import com.velora.api.catalog.importer.domain.ImportBatchStatus;
import com.velora.api.catalog.importer.domain.ImportRowAction;
import com.velora.api.catalog.importer.domain.ImportRowStatus;
import com.velora.api.catalog.importer.dto.ImportBatchResponse;
import com.velora.api.catalog.importer.dto.ImportCommitResponse;
import com.velora.api.catalog.importer.dto.ImportMessage;
import com.velora.api.catalog.importer.dto.ImportRowData;
import com.velora.api.catalog.importer.dto.ImportRowEditRequest;
import com.velora.api.catalog.importer.dto.ImportRowResponse;
import com.velora.api.catalog.importer.dto.ImportRowUpdateResponse;
import com.velora.api.catalog.importer.dto.ImportSkuCheckResponse;
import com.velora.api.catalog.importer.repository.ImportBatchRepository;
import com.velora.api.catalog.importer.repository.ImportBatchRowRepository;
import com.velora.api.catalog.importer.service.ImportRowProcessor.Lookup;
import com.velora.api.catalog.importer.service.ImportRowProcessor.Parsed;
import com.velora.api.catalog.importer.service.ImportRowProcessor.Resolved;
import com.velora.api.catalog.importer.service.ImportSheetReader.RawRow;
import com.velora.api.catalog.repository.ProductVariantRepository;
import com.velora.api.catalog.service.admin.ProductAdminService;
import com.velora.api.catalog.service.admin.VariantAdminService;
import com.velora.api.common.dto.PageResponse;
import com.velora.api.common.exception.BusinessException;
import com.velora.api.common.exception.ErrorCode;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Bulk product upload in two steps, so nothing reaches the catalogue unreviewed:
 *
 * <ol>
 *   <li>{@link #preview}: the sheet is checked against the template, every row is parsed and
 *       validated, and the result is kept as an {@code import_batch}. The catalogue is untouched.</li>
 *   <li>{@link #commit}: only when EVERY row is valid. All products are written in one
 *       transaction, so a failure part-way leaves the catalogue exactly as it was.</li>
 * </ol>
 *
 * <p>Writing goes through {@link ProductAdminService} and {@link VariantAdminService}, never around
 * them: slug generation, the Arabic {@code search_text}, SKU uniqueness, the inventory row and the
 * price audit all stay in one place.
 */
@Service
public class ProductImportService {

    private static final Logger log = LoggerFactory.getLogger(ProductImportService.class);

    /** A preview older than this is refused: the catalogue may have moved on since it was checked. */
    private static final Duration PREVIEW_LIFETIME = Duration.ofHours(24);
    private static final int ROWS_NAMED_IN_ERROR = 5;

    private static final TypeReference<List<ImportMessage>> MESSAGES = new TypeReference<>() { };

    private final ImportSheetReader reader;
    private final ImportRowProcessor processor;
    private final ImportTemplateProvider templateProvider;
    private final ImportBatchRepository batchRepository;
    private final ImportBatchRowRepository rowRepository;
    private final ProductAdminService productService;
    private final VariantAdminService variantService;
    private final ProductVariantRepository variantRepository;
    private final ObjectMapper mapper;

    public ProductImportService(ImportSheetReader reader,
                                ImportRowProcessor processor,
                                ImportTemplateProvider templateProvider,
                                ImportBatchRepository batchRepository,
                                ImportBatchRowRepository rowRepository,
                                ProductAdminService productService,
                                VariantAdminService variantService,
                                ProductVariantRepository variantRepository,
                                ObjectMapper mapper) {
        this.reader = reader;
        this.processor = processor;
        this.templateProvider = templateProvider;
        this.batchRepository = batchRepository;
        this.rowRepository = rowRepository;
        this.productService = productService;
        this.variantService = variantService;
        this.variantRepository = variantRepository;
        this.mapper = mapper;
    }

    // ------------------------------------------------------------------- template

    /** The furniture workbook, trimmed to headers only: the one file shape the upload accepts. */
    public byte[] template() {
        return templateProvider.template();
    }

    // -------------------------------------------------------------------- preview

    @Transactional
    public ImportBatchResponse preview(String fileName, byte[] content, Long actorId) {
        List<RawRow> raws = reader.read(content);
        Lookup lookup = processor.loadLookup();

        List<Evaluated> evaluated = new ArrayList<>();
        for (RawRow raw : raws) {
            Parsed parsed = processor.parse(raw);
            Resolved resolved = processor.resolve(parsed.data(), lookup);
            List<ImportMessage> messages = new ArrayList<>(parsed.messages());
            messages.addAll(resolved.messages());
            evaluated.add(new Evaluated(raw.rowNo(), resolved, locate(messages, raw.rowNo(), raw.seoRowNo())));
        }
        flagDuplicatesInFile(evaluated);

        ImportBatch batch = new ImportBatch();
        batch.setFileName(fileName == null || fileName.isBlank() ? "products.xlsx" : fileName);
        batch.setCreatedBy(actorId);
        batch.setStatus(ImportBatchStatus.PREVIEW);
        applyCounts(batch, evaluated);
        batchRepository.save(batch);

        List<ImportBatchRow> rows = new ArrayList<>();
        for (Evaluated e : evaluated) {
            ImportBatchRow row = new ImportBatchRow();
            row.setBatchId(batch.getId());
            row.setRowNo(e.rowNo());
            row.setSku(e.resolved().data().sku());
            row.setAction(e.resolved().action());
            row.setStatus(e.hasErrors() ? ImportRowStatus.ERROR : ImportRowStatus.VALID);
            row.setData(toJson(e.resolved().data()));
            row.setMessages(e.messages().isEmpty() ? null : toJson(e.messages()));
            rows.add(row);
        }
        rowRepository.saveAll(rows);

        log.info("Import batch {} previewed: {} rows, {} errors", batch.getId(), batch.getTotalRows(),
                batch.getErrorRows());
        return toResponse(batch);
    }

    // ---------------------------------------------------------------------- query

    @Transactional(readOnly = true)
    public ImportBatchResponse get(Long id) {
        return toResponse(load(id));
    }

    @Transactional(readOnly = true)
    public PageResponse<ImportBatchResponse> list(Pageable pageable) {
        return PageResponse.from(batchRepository.findAll(pageable), this::toResponse);
    }

    @Transactional(readOnly = true)
    public PageResponse<ImportRowResponse> rows(Long id, ImportRowStatus status, Pageable pageable) {
        load(id);
        Page<ImportBatchRow> page = status == null
                ? rowRepository.findByBatchIdOrderByStatusAscRowNoAsc(id, pageable)
                : rowRepository.findByBatchIdAndStatusOrderByRowNoAsc(id, status, pageable);
        return PageResponse.from(page, this::toRowResponse);
    }

    // ----------------------------------------------------------------------- edit

    /**
     * Fixes one row of a preview without uploading the sheet again. Only the values sent are replaced;
     * the row is then run through exactly the same cell checks and catalogue checks as an uploaded one,
     * and the whole batch is re-counted, because a duplicate SKU or slug involves other rows too.
     */
    @Transactional
    public ImportRowUpdateResponse editRow(Long batchId, int rowNo, ImportRowEditRequest request) {
        ImportBatch batch = load(batchId);
        if (batch.getStatus() != ImportBatchStatus.PREVIEW) {
            throw new BusinessException(ErrorCode.IMPORT_BATCH_NOT_PENDING);
        }
        List<ImportBatchRow> rows = rowRepository.findByBatchIdOrderByRowNoAsc(batchId);
        ImportBatchRow target = rows.stream().filter(r -> r.getRowNo() == rowNo).findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.IMPORT_ROW_NOT_FOUND));

        ImportRowData current = fromJson(target.getData(), ImportRowData.class);
        Map<String, String> values = ImportRowValues.of(current);
        for (Map.Entry<String, String> change : request.values().entrySet()) {
            if (!ImportRowValues.isEditable(change.getKey())) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                        "'" + change.getKey() + "' is not an editable import field");
            }
            values.put(change.getKey(), change.getValue() == null ? "" : change.getValue().trim());
        }

        RawRow raw = new RawRow(rowNo, current.seoRowNo(), values);
        Parsed parsed = processor.parse(raw);
        Resolved resolved = processor.resolve(parsed.data(), processor.loadLookup());
        List<ImportMessage> messages = new ArrayList<>(parsed.messages());
        messages.addAll(resolved.messages());

        target.setSku(resolved.data().sku());
        target.setAction(resolved.action());
        target.setData(toJson(resolved.data()));
        target.setMessages(toJson(locate(messages, rowNo, current.seoRowNo())));

        Map<Integer, String> before = new HashMap<>();
        rows.forEach(r -> before.put(r.getRowNo(), r.getStatus() + "|" + r.getMessages()));

        refreshBatch(batch, rows);

        List<ImportRowResponse> affected = rows.stream()
                .filter(r -> r != target && !before.get(r.getRowNo()).equals(r.getStatus() + "|" + r.getMessages()))
                .map(this::toRowResponse)
                .toList();
        return new ImportRowUpdateResponse(toResponse(batch), toRowResponse(target), affected);
    }

    // ---------------------------------------------------------------------- SKU check

    /**
     * Whether a SKU typed into the row editor is free, so the form can say so before the row is saved:
     * taken by another row of this file (an error), already sold in the catalogue (the row would update
     * that product), or unique.
     */
    @Transactional(readOnly = true)
    public ImportSkuCheckResponse checkSku(Long batchId, String sku, Integer rowNo) {
        load(batchId);
        String key = sku == null ? "" : sku.trim().toUpperCase(Locale.ROOT);
        if (key.isEmpty()) {
            return new ImportSkuCheckResponse(key, "EMPTY", List.of(), null, null);
        }
        List<Integer> others = rowRepository.findByBatchIdAndSku(batchId, key).stream()
                .map(ImportBatchRow::getRowNo)
                .filter(n -> !n.equals(rowNo))
                .sorted()
                .toList();
        if (!others.isEmpty()) {
            return new ImportSkuCheckResponse(key, "DUPLICATE_IN_FILE", others, null, null);
        }
        return variantRepository.findBySku(key)
                .map(v -> new ImportSkuCheckResponse(key, "IN_CATALOG", List.of(), v.getProduct().getId(),
                        v.getProduct().nameFor("ar")))
                .orElseGet(() -> new ImportSkuCheckResponse(key, "AVAILABLE", List.of(), null, null));
    }

    /**
     * Re-derives every row's status and the batch totals after an edit. Each row keeps the findings it
     * already has, except the duplicate-in-file ones, which depend on the other rows and are worked out
     * again over the whole batch.
     */
    private void refreshBatch(ImportBatch batch, List<ImportBatchRow> rows) {
        List<Evaluated> evaluated = new ArrayList<>();
        for (ImportBatchRow row : rows) {
            ImportRowData data = fromJson(row.getData(), ImportRowData.class);
            List<ImportMessage> kept = new ArrayList<>(row.getMessages() == null
                    ? List.<ImportMessage>of() : fromJson(row.getMessages(), MESSAGES));
            kept.removeIf(m -> m.code() != null && m.code().startsWith("DUPLICATE_"));
            evaluated.add(new Evaluated(row.getRowNo(),
                    new Resolved(data, row.getAction(), row.getProductId(), null, List.of()), kept));
        }
        flagDuplicatesInFile(evaluated);

        for (int i = 0; i < rows.size(); i++) {
            Evaluated e = evaluated.get(i);
            ImportBatchRow row = rows.get(i);
            row.setStatus(e.hasErrors() ? ImportRowStatus.ERROR : ImportRowStatus.VALID);
            row.setMessages(e.messages().isEmpty() ? null : toJson(e.messages()));
        }
        applyCounts(batch, evaluated);
        rowRepository.saveAll(rows);
        batchRepository.save(batch);
    }

    private List<ImportMessage> locate(List<ImportMessage> messages, int rowNo, Integer seoRowNo) {
        return new ArrayList<>(messages.stream().map(m -> m.locatedAt(rowNo, seoRowNo)).toList());
    }

    // --------------------------------------------------------------------- commit

    /**
     * Submits a reviewed sheet. Refused outright if any row is an error. The rows are checked a
     * second time here, against the catalogue as it is NOW: a SKU or slug taken since the preview
     * must stop the submit, not surface as a database error halfway through.
     */
    @Transactional
    public ImportCommitResponse commit(Long id, Long actorId) {
        ImportBatch batch = load(id);
        if (batch.getStatus() != ImportBatchStatus.PREVIEW) {
            throw new BusinessException(ErrorCode.IMPORT_BATCH_NOT_PENDING);
        }
        if (batch.getCreatedAt().isBefore(OffsetDateTime.now(ZoneOffset.UTC).minus(PREVIEW_LIFETIME))) {
            throw new BusinessException(ErrorCode.IMPORT_BATCH_EXPIRED);
        }
        if (batch.getErrorRows() > 0 || batch.getTotalRows() == 0) {
            throw new BusinessException(ErrorCode.IMPORT_HAS_ERRORS,
                    batch.getErrorRows() + " row(s) have errors. Fix the sheet and upload it again; "
                            + "nothing was saved");
        }

        List<ImportBatchRow> rows = rowRepository.findByBatchIdOrderByRowNoAsc(id);
        Lookup lookup = processor.loadLookup();

        List<Evaluated> evaluated = new ArrayList<>();
        for (ImportBatchRow row : rows) {
            Resolved resolved = processor.resolve(fromJson(row.getData(), ImportRowData.class), lookup);
            evaluated.add(new Evaluated(row.getRowNo(), resolved, new ArrayList<>(resolved.messages())));
        }
        flagDuplicatesInFile(evaluated);

        List<Evaluated> stale = evaluated.stream().filter(Evaluated::hasErrors).toList();
        if (!stale.isEmpty()) {
            throw new BusinessException(ErrorCode.IMPORT_HAS_ERRORS,
                    "The catalogue changed since the preview. " + describe(stale)
                            + " Upload the file again; nothing was saved");
        }

        int created = 0;
        for (int i = 0; i < rows.size(); i++) {
            ImportBatchRow row = rows.get(i);
            try {
                row.setProductId(create(evaluated.get(i).resolved(), lookup, actorId));
                row.setAction(ImportRowAction.CREATE);
                created++;
            } catch (BusinessException e) {
                // The whole transaction rolls back; say which row stopped it.
                throw new BusinessException(e.getErrorCode(),
                        "Row " + row.getRowNo() + " (" + row.getSku() + "): " + e.getMessage()
                                + ". Nothing was saved");
            }
        }

        rowRepository.saveAll(rows);
        batch.setStatus(ImportBatchStatus.COMMITTED);
        batch.setCommittedAt(OffsetDateTime.now(ZoneOffset.UTC));
        batchRepository.save(batch);

        log.info("Import batch {} committed: {} products created", id, created);
        return new ImportCommitResponse(id, created,
                "The products are saved as DRAFT. Publish them from the product list.");
    }

    @Transactional
    public void discard(Long id) {
        ImportBatch batch = load(id);
        if (batch.getStatus() != ImportBatchStatus.PREVIEW) {
            throw new BusinessException(ErrorCode.IMPORT_BATCH_NOT_PENDING);
        }
        batch.setStatus(ImportBatchStatus.DISCARDED);
        batchRepository.save(batch);
    }

    // ----------------------------------------------------------------- writing rows

    private Long create(Resolved resolved, Lookup lookup, Long actorId) {
        ImportRowData d = resolved.data();

        List<TranslationRequest> translations = new ArrayList<>();
        translations.add(new TranslationRequest("ar", d.nameAr(), d.shortAr(), d.descAr(),
                d.metaTitleAr(), d.metaDescAr()));
        if (d.nameEn() != null) {
            translations.add(new TranslationRequest("en", d.nameEn(), d.shortEn(), d.descEn(),
                    d.metaTitleEn(), d.metaDescEn()));
        }

        List<SpecificationRequest> specs = d.specs().entrySet().stream()
                .map(e -> new SpecificationRequest(lookup.attributeId(e.getKey()), null, e.getValue()))
                .toList();

        ProductAdminResponse product = productService.create(new ProductCreateRequest(
                d.categoryId(), null, d.slug(), translations, false, false, specs,
                d.fulfillmentType(), d.shippingSizeClass(), d.requiresAssembly(), d.assemblyFee()));

        variantService.saveVariants(product.id(), new VariantSaveRequest(List.of(new VariantItem(
                null, d.sku(), null, d.price(), d.compareAtPrice(), null, null,
                d.weightGrams(), List.of(), d.stock(), null))), actorId);
        return product.id();
    }

    // -------------------------------------------------------------------- internals

    /** A row, its resolved data, and every finding about it. */
    private record Evaluated(int rowNo, Resolved resolved, List<ImportMessage> messages) {

        boolean hasErrors() {
            return messages.stream().anyMatch(ImportMessage::isError);
        }

        boolean hasWarnings() {
            return messages.stream().anyMatch(m -> !m.isError());
        }
    }

    /** The same SKU or slug twice in one file: every row involved is an error, none is "the first". */
    private void flagDuplicatesInFile(List<Evaluated> evaluated) {
        flagDuplicates(evaluated, e -> e.resolved().data().sku() == null ? null
                        : e.resolved().data().sku().toUpperCase(Locale.ROOT),
                "sku", "DUPLICATE_SKU", "This SKU appears more than once in the file (rows %s)");
        flagDuplicates(evaluated, e -> e.resolved().data().slug(),
                "slug", "DUPLICATE_SLUG", "This slug appears more than once in the file (rows %s)");
    }

    private void flagDuplicates(List<Evaluated> evaluated,
                                java.util.function.Function<Evaluated, String> key,
                                String column, String code, String template) {
        Map<String, List<Evaluated>> groups = new HashMap<>();
        for (Evaluated e : evaluated) {
            String k = key.apply(e);
            if (k != null) {
                groups.computeIfAbsent(k, x -> new ArrayList<>()).add(e);
            }
        }
        for (List<Evaluated> group : groups.values()) {
            if (group.size() < 2) {
                continue;
            }
            String rowNumbers = group.stream().map(g -> String.valueOf(g.rowNo()))
                    .collect(Collectors.joining(", "));
            for (Evaluated e : group) {
                e.messages().add(ImportMessage.error(column, code, template.formatted(rowNumbers))
                        .locatedAt(e.rowNo(), e.resolved().data().seoRowNo()));
            }
        }
    }

    private void applyCounts(ImportBatch batch, Collection<Evaluated> evaluated) {
        int errors = 0;
        int warnings = 0;
        for (Evaluated e : evaluated) {
            if (e.hasErrors()) {
                errors++;
            } else if (e.hasWarnings()) {
                warnings++;
            }
        }
        batch.setTotalRows(evaluated.size());
        batch.setErrorRows(errors);
        batch.setWarningRows(warnings);
        batch.setCreateRows(evaluated.size());
        batch.setUpdateRows(0);
    }

    private String describe(List<Evaluated> failing) {
        return failing.stream().limit(ROWS_NAMED_IN_ERROR)
                .map(e -> "Row " + e.rowNo() + ": " + e.messages().stream().filter(ImportMessage::isError)
                        .map(ImportMessage::message).findFirst().orElse("invalid") + ".")
                .collect(Collectors.joining(" "));
    }

    private ImportBatch load(Long id) {
        return batchRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.IMPORT_BATCH_NOT_FOUND));
    }

    private ImportBatchResponse toResponse(ImportBatch b) {
        return new ImportBatchResponse(b.getId(), b.getFileName(), b.getStatus().name(), b.getTotalRows(),
                b.getTotalRows() - b.getErrorRows(), b.getErrorRows(),
                b.getWarningRows(), b.isSubmittable(), b.getCreatedAt(), b.getCommittedAt());
    }

    private ImportRowResponse toRowResponse(ImportBatchRow row) {
        ImportRowData data = fromJson(row.getData(), ImportRowData.class);
        List<ImportMessage> messages = row.getMessages() == null
                ? List.of() : fromJson(row.getMessages(), MESSAGES);
        List<ImportRowResponse.Specification> specs = ImportTemplate.COLUMNS.stream()
                .filter(ImportTemplate.Column::isSpec)
                .map(c -> new ImportRowResponse.Specification(c.attributeCode(), c.key(), c.header(),
                        specKind(c), data.specs().get(c.attributeCode())))
                .toList();
        return new ImportRowResponse(row.getRowNo(), row.getSku(),
                row.getAction() == null ? null : row.getAction().name(), row.getStatus().name(),
                data, ImportRowValues.of(data), specs, messages, row.getProductId());
    }

    private static String specKind(ImportTemplate.Column column) {
        return switch (column.kind()) {
            case SPEC_NUMBER -> "NUMBER";
            case SPEC_BOOLEAN -> "BOOLEAN";
            default -> "TEXT";
        };
    }

    private String toJson(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not store an import row", e);
        }
    }

    private <T> T fromJson(String json, Class<T> type) {
        try {
            return mapper.readValue(json, type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not read a stored import row", e);
        }
    }

    private <T> T fromJson(String json, TypeReference<T> type) {
        try {
            return mapper.readValue(json, type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not read stored import messages", e);
        }
    }
}
