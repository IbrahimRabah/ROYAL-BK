package com.velora.api.catalog.imageimport.service;

import com.velora.api.catalog.domain.Product;
import com.velora.api.catalog.domain.ProductStatus;
import com.velora.api.catalog.domain.ProductVariant;
import com.velora.api.catalog.imageimport.dto.ImageBulkResponse;
import com.velora.api.catalog.imageimport.dto.ImageBulkResponse.Counts;
import com.velora.api.catalog.imageimport.dto.ImageBulkResponse.FileResult;
import com.velora.api.catalog.imageimport.dto.ImageImportMode;
import com.velora.api.catalog.imageimport.dto.ImageMatchRequest;
import com.velora.api.catalog.imageimport.dto.ImageMatchRequest.FileRef;
import com.velora.api.catalog.imageimport.dto.ImageMatchResponse;
import com.velora.api.catalog.imageimport.dto.ImageMatchResponse.Conflict;
import com.velora.api.catalog.imageimport.dto.ImageMatchResponse.ConflictFile;
import com.velora.api.catalog.imageimport.dto.ImageMatchResponse.Item;
import com.velora.api.catalog.imageimport.dto.ImageMatchResponse.MatchedFile;
import com.velora.api.catalog.imageimport.dto.ImageMatchResponse.ProductMatch;
import com.velora.api.catalog.imageimport.dto.ImageMatchResponse.Summary;
import com.velora.api.catalog.imageimport.dto.ImageMatchResponse.Unmatched;
import com.velora.api.catalog.imageimport.dto.ImageMatchResponse.WithoutImage;
import com.velora.api.catalog.imageimport.service.ImageNameMatcher.Ambiguous;
import com.velora.api.catalog.imageimport.service.ImageNameMatcher.Kind;
import com.velora.api.catalog.imageimport.service.ImageNameMatcher.Match;
import com.velora.api.catalog.imageimport.service.ImageNameMatcher.ParsedName;
import com.velora.api.catalog.repository.ProductRepository;
import com.velora.api.catalog.repository.ProductVariantRepository;
import com.velora.api.common.exception.BusinessException;
import com.velora.api.common.exception.ErrorCode;
import com.velora.api.common.storage.ImageSniffer;
import com.velora.api.common.storage.StorageProperties;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

/**
 * Bulk image import, matched to products by file name = SKU.
 *
 * <ul>
 *   <li>{@link #match}: file NAMES only, no bytes. Says which file belongs to which product and what would
 *       happen, so the person sees problems before anything is uploaded.</li>
 *   <li>{@link #upload}: a few files at a time. Deliberately NOT one transaction: each file is its own
 *       transaction in {@link ProductImageAttacher}, so one bad file costs that file and nothing else, and a
 *       re-run picks up where a failed run stopped.</li>
 * </ul>
 */
@Service
public class ProductImageImportService {

    private static final Logger log = LoggerFactory.getLogger(ProductImageImportService.class);

    /** The upload request limit is 10 MB; the screen sends resized pictures, a handful at a time. */
    static final int MAX_FILES_PER_REQUEST = 12;
    static final int WITHOUT_IMAGE_SHOWN = 200;
    /** SQL Server allows 2100 parameters in one statement. */
    private static final int LOOKUP_CHUNK = 1000;
    private static final Pattern RUN_ID = Pattern.compile("^[0-9a-fA-F-]{36}$");

    private final ProductVariantRepository variantRepository;
    private final ProductRepository productRepository;
    private final ProductImageAttacher attacher;
    private final StorageProperties storageProperties;

    public ProductImageImportService(ProductVariantRepository variantRepository,
                                     ProductRepository productRepository,
                                     ProductImageAttacher attacher,
                                     StorageProperties storageProperties) {
        this.variantRepository = variantRepository;
        this.productRepository = productRepository;
        this.attacher = attacher;
        this.storageProperties = storageProperties;
    }

    // ---------------------------------------------------------------------- match

    /** One file of the request, read. */
    private record Entry(FileRef ref, ParsedName parsed, Kind kind) {
    }

    @Transactional(readOnly = true)
    public ImageMatchResponse match(ImageMatchRequest request) {
        List<Entry> entries = new ArrayList<>();
        Set<String> wanted = new LinkedHashSet<>();
        for (FileRef ref : request.files()) {
            ParsedName parsed = ImageNameMatcher.parse(ref.name());
            Kind kind = ImageNameMatcher.kind(parsed);
            entries.add(new Entry(ref, parsed, kind));
            if (kind != Kind.NOT_AN_IMAGE) {
                wanted.addAll(ImageNameMatcher.candidates(parsed));
                wanted.addAll(ImageNameMatcher.suggestionCandidates(parsed));
            }
        }
        Map<String, ProductVariant> bySku = variantsBySku(wanted);

        Map<String, List<Matched>> bySkuGroup = new TreeMap<>();
        List<Unmatched> unmatched = new ArrayList<>();
        for (Entry e : entries) {
            if (e.kind() == Kind.NOT_AN_IMAGE) {
                unmatched.add(unmatched(e, "NOT_AN_IMAGE", null, null, null));
                continue;
            }
            Optional<Match> found;
            try {
                found = ImageNameMatcher.resolve(e.parsed(), bySku::containsKey);
            } catch (Ambiguous ambiguous) {
                unmatched.add(unmatched(e, "AMBIGUOUS_NAME", null, null, null));
                continue;
            }
            if (found.isEmpty()) {
                Optional<Match> near = ImageNameMatcher.suggest(e.parsed(), bySku::containsKey);
                unmatched.add(unmatched(e, e.kind() == Kind.UNSUPPORTED_FORMAT ? "UNSUPPORTED_FORMAT" : "NO_PRODUCT",
                        null, near.map(Match::sku).orElse(null), near.map(Match::position).orElse(null)));
            } else if (e.kind() == Kind.UNSUPPORTED_FORMAT) {
                unmatched.add(unmatched(e, "UNSUPPORTED_FORMAT", found.get().sku(), null, null));
            } else {
                bySkuGroup.computeIfAbsent(found.get().sku(), k -> new ArrayList<>())
                        .add(new Matched(e, found.get().position()));
            }
        }

        Set<Long> productIds = new LinkedHashSet<>();
        bySkuGroup.keySet().forEach(sku -> productIds.add(bySku.get(sku).getProduct().getId()));
        Map<Long, Integer> imageCounts = imageCounts(productIds);

        List<ProductMatch> products = new ArrayList<>();
        List<Conflict> conflicts = new ArrayList<>();
        int toUpload = 0;
        int productsSkipped = 0;
        Set<Long> receivingFirstImage = new LinkedHashSet<>();

        for (Map.Entry<String, List<Matched>> group : bySkuGroup.entrySet()) {
            ProductVariant variant = bySku.get(group.getKey());
            Product product = variant.getProduct();
            int existing = imageCounts.getOrDefault(product.getId(), 0);
            List<Matched> files = group.getValue();
            files.sort(Comparator.comparingInt(Matched::position).thenComparing(m -> m.entry().ref().name()));

            // Two files claiming the same place in one gallery: none of them is used, the person decides.
            Map<Integer, List<Matched>> byPosition = new LinkedHashMap<>();
            files.forEach(m -> byPosition.computeIfAbsent(m.position(), k -> new ArrayList<>()).add(m));
            Set<Matched> clashing = new LinkedHashSet<>();
            List<ConflictFile> clashFiles = new ArrayList<>();
            byPosition.values().stream().filter(l -> l.size() > 1).forEach(l -> {
                clashing.addAll(l);
                l.forEach(m -> clashFiles.add(new ConflictFile(m.entry().ref().name(), m.entry().ref().folder(),
                        m.position(), m.entry().ref().sizeBytes())));
            });
            if (!clashFiles.isEmpty()) {
                conflicts.add(new Conflict(group.getKey(), "DUPLICATE_POSITION", clashFiles));
            }

            String action = "ATTACH";
            String blocked = null;
            if (product.getArchivedAt() != null || product.getStatus() == ProductStatus.ARCHIVED) {
                action = "BLOCKED";
                blocked = "PRODUCT_ARCHIVED";
            } else if (request.mode() == ImageImportMode.SKIP && existing > 0) {
                action = "SKIP_HAS_IMAGES";
                productsSkipped++;
            }

            int room = request.mode() == ImageImportMode.ADD
                    ? ProductImageAttacher.MAX_IMAGES_PER_PRODUCT - existing
                    : ProductImageAttacher.MAX_IMAGES_PER_PRODUCT;
            int taken = 0;
            List<MatchedFile> out = new ArrayList<>();
            for (Matched m : files) {
                String problem = null;
                boolean upload = "ATTACH".equals(action);
                if (clashing.contains(m)) {
                    problem = "DUPLICATE_POSITION";
                    upload = false;
                } else if (upload && ++taken > room) {
                    problem = "TOO_MANY_IMAGES";
                    upload = false;
                }
                if (upload) {
                    toUpload++;
                }
                out.add(new MatchedFile(m.entry().ref().name(), m.entry().ref().folder(), m.position(),
                        m.entry().ref().sizeBytes(), upload, problem));
            }
            if (existing == 0 && out.stream().anyMatch(MatchedFile::upload)) {
                receivingFirstImage.add(product.getId());
            }
            boolean several = product.getVariants().stream().filter(v -> v.getArchivedAt() == null).count() > 1;
            products.add(new ProductMatch(group.getKey(), product.getId(), several ? variant.getId() : null,
                    name(product, "ar"), name(product, "en"),
                    product.getCategory() == null ? null : product.getCategory().nameFor("ar"),
                    product.getStatus().name(), existing, action, blocked, out));
        }

        long withoutImage = Math.max(0, productRepository.countWithoutImages() - receivingFirstImage.size());
        List<Item> shown = new ArrayList<>();
        for (Product p : productRepository.findWithoutImages(PageRequest.of(0, WITHOUT_IMAGE_SHOWN + receivingFirstImage.size()))) {
            if (shown.size() < WITHOUT_IMAGE_SHOWN && !receivingFirstImage.contains(p.getId())) {
                shown.add(new Item(p.getId(), firstSku(p), name(p, "ar"), name(p, "en")));
            }
        }

        Summary summary = new Summary(entries.size(), toUpload, productsSkipped, products.size(), conflicts.size(),
                unmatched.size(), (int) withoutImage);
        log.info("Image match: {} files, {} products, {} to upload, {} unmatched", entries.size(), products.size(),
                toUpload, unmatched.size());
        return new ImageMatchResponse(request.mode().name(), summary, products, conflicts, unmatched,
                new WithoutImage((int) withoutImage, shown));
    }

    private record Matched(Entry entry, int position) {
    }

    private static Unmatched unmatched(Entry e, String reason, String sku, String suggestion, Integer position) {
        return new Unmatched(e.ref().name(), e.ref().folder(), e.ref().sizeBytes(), reason, sku, suggestion, position);
    }

    private static String name(Product product, String locale) {
        var t = product.getTranslations().get(locale);
        return t == null ? null : t.getName();
    }

    private static String firstSku(Product product) {
        return product.getVariants().stream().filter(v -> v.getArchivedAt() == null).findFirst()
                .map(ProductVariant::getSku).orElse(null);
    }

    private Map<Long, Integer> imageCounts(Set<Long> productIds) {
        Map<Long, Integer> counts = new HashMap<>();
        if (productIds.isEmpty()) {
            return counts;
        }
        List<Long> ids = new ArrayList<>(productIds);
        for (int from = 0; from < ids.size(); from += LOOKUP_CHUNK) {
            for (Object[] row : productRepository.countImagesByProduct(
                    ids.subList(from, Math.min(ids.size(), from + LOOKUP_CHUNK)))) {
                counts.put((Long) row[0], ((Number) row[1]).intValue());
            }
        }
        return counts;
    }

    /** SKU (upper case) to variant, for every SKU in {@code skus} that exists. Archived variants count: SKUs are never reused. */
    private Map<String, ProductVariant> variantsBySku(Set<String> skus) {
        Map<String, ProductVariant> found = new HashMap<>();
        List<String> list = new ArrayList<>(skus);
        for (int from = 0; from < list.size(); from += LOOKUP_CHUNK) {
            for (ProductVariant v : variantRepository.findBySkuInWithProduct(
                    list.subList(from, Math.min(list.size(), from + LOOKUP_CHUNK)))) {
                found.put(v.getSku().toUpperCase(java.util.Locale.ROOT), v);
            }
        }
        return found;
    }

    // --------------------------------------------------------------------- upload

    /** A request file after the checks that need no database. */
    private record Prepared(MultipartFile file, ParsedName parsed, String hash) {
    }

    /**
     * Attaches the files of one request. Never throws for a bad file: every file gets a result, so the screen can
     * show what was attached, skipped and rejected and retry only what FAILED.
     */
    public ImageBulkResponse upload(String runId, ImageImportMode mode, List<MultipartFile> files) {
        if (runId == null || !RUN_ID.matcher(runId).matches()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "runId must be a UUID");
        }
        if (files == null || files.isEmpty()) {
            throw new BusinessException(ErrorCode.FILE_REQUIRED);
        }
        if (files.size() > MAX_FILES_PER_REQUEST) {
            throw new BusinessException(ErrorCode.IMAGE_IMPORT_TOO_MANY_FILES,
                    "At most " + MAX_FILES_PER_REQUEST + " files per request");
        }

        Set<String> wanted = new LinkedHashSet<>();
        files.forEach(f -> wanted.addAll(ImageNameMatcher.candidates(ImageNameMatcher.parse(f.getOriginalFilename()))));
        Map<String, ProductVariant> bySku = variantsBySku(wanted);

        List<FileResult> results = new ArrayList<>();
        int attached = 0;
        int skipped = 0;
        int rejected = 0;
        int failed = 0;
        for (MultipartFile file : files) {
            FileResult result = one(runId, mode, file, bySku);
            results.add(result);
            switch (result.status()) {
                case "ATTACHED" -> attached++;
                case "SKIPPED" -> skipped++;
                case "REJECTED" -> rejected++;
                default -> failed++;
            }
        }
        log.info("Image import run {}: {} files, {} attached, {} skipped, {} rejected, {} failed", runId,
                files.size(), attached, skipped, rejected, failed);
        return new ImageBulkResponse(runId, mode.name(), new Counts(attached, skipped, rejected, failed), results);
    }

    private FileResult one(String runId, ImageImportMode mode, MultipartFile file, Map<String, ProductVariant> bySku) {
        String name = file.getOriginalFilename() == null ? "" : file.getOriginalFilename();
        ParsedName parsed = ImageNameMatcher.parse(name);

        Kind kind = ImageNameMatcher.kind(parsed);
        if (kind == Kind.NOT_AN_IMAGE) {
            return rejected(name, "NOT_AN_IMAGE", null, null);
        }
        Match match;
        try {
            match = ImageNameMatcher.resolve(parsed, bySku::containsKey).orElse(null);
        } catch (Ambiguous ambiguous) {
            return rejected(name, "AMBIGUOUS_NAME", null, null);
        }
        if (match == null) {
            return rejected(name, "NO_MATCH", null, null);
        }
        ProductVariant variant = bySku.get(match.sku());
        Long productId = variant.getProduct().getId();
        if (kind == Kind.UNSUPPORTED_FORMAT) {
            return rejected(name, "UNSUPPORTED_FORMAT", match.sku(), productId);
        }

        // What the bytes are, not what the name or the sender says. The same checks the single upload does,
        // reported as codes instead of exceptions.
        if (file.isEmpty()) {
            return rejected(name, "EMPTY_FILE", match.sku(), productId);
        }
        if (file.getSize() > storageProperties.getMaxFileSizeBytes()) {
            return rejected(name, "TOO_LARGE", match.sku(), productId);
        }
        byte[] bytes;
        try (InputStream in = file.getInputStream()) {
            bytes = in.readAllBytes();
        } catch (IOException e) {
            return new FileResult(name, "FAILED", "SERVER_ERROR", match.sku(), productId, null, null);
        }
        byte[] head = java.util.Arrays.copyOf(bytes, Math.min(bytes.length, ImageSniffer.HEAD_BYTES));
        if (ImageSniffer.detect(head).isEmpty()) {
            return rejected(name, ImageSniffer.isHeic(head) ? "UNSUPPORTED_FORMAT" : "NOT_AN_IMAGE",
                    match.sku(), productId);
        }

        try {
            ProductImageAttacher.Outcome outcome = attacher.attach(new ProductImageAttacher.Request(productId,
                    variant.getId(), match.position(), file, sha256(bytes), mode, runId));
            return new FileResult(name, outcome.status().name(), outcome.code(), match.sku(), productId,
                    outcome.imageId(), outcome.url());
        } catch (BusinessException e) {
            return rejected(name, "NOT_AN_IMAGE", match.sku(), productId);
        } catch (RuntimeException e) {
            log.error("Image import: could not attach {} to product {}", name, productId, e);
            return new FileResult(name, "FAILED", "SERVER_ERROR", match.sku(), productId, null, null);
        }
    }

    private static FileResult rejected(String name, String code, String sku, Long productId) {
        return new FileResult(name, "REJECTED", code, sku, productId, null, null);
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is always available", e);
        }
    }
}
