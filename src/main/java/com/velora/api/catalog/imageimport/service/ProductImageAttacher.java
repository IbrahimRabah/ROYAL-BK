package com.velora.api.catalog.imageimport.service;

import com.velora.api.catalog.domain.Product;
import com.velora.api.catalog.domain.ProductImage;
import com.velora.api.catalog.domain.ProductStatus;
import com.velora.api.catalog.domain.ProductTranslation;
import com.velora.api.catalog.imageimport.dto.ImageImportMode;
import com.velora.api.catalog.repository.ProductRepository;
import com.velora.api.catalog.repository.ProductVariantRepository;
import com.velora.api.common.storage.StorageService;
import com.velora.api.common.storage.StorageTransactionSupport;
import com.velora.api.common.storage.StoredFile;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

/**
 * Attaches ONE already-validated file to ONE product, in its own transaction.
 *
 * <p>A separate bean from {@link ProductImageImportService} on purpose: one transaction per file is what makes
 * a bad file (or a failed disk write) cost exactly that file, and a transaction proxy only works across beans.
 *
 * <p>Inside the transaction the product row is locked, then everything is decided on what is really stored:
 * whether the product already had images before this run, whether the same picture is already there, whether
 * the gallery is full, which image is the main one. The lock is what makes the 20-image limit a limit and not
 * a race between two requests.
 */
@Service
public class ProductImageAttacher {

    static final int MAX_IMAGES_PER_PRODUCT = 20;
    private static final int ALT_MAX = 255;

    public enum Status { ATTACHED, SKIPPED, REJECTED }

    /** The outcome for one file. {@code code} is null when attached. */
    public record Outcome(Status status, String code, Long imageId, String url) {

        static Outcome attached(Long imageId, String url) {
            return new Outcome(Status.ATTACHED, null, imageId, url);
        }

        static Outcome skipped(String code) {
            return new Outcome(Status.SKIPPED, code, null, null);
        }

        static Outcome rejected(String code) {
            return new Outcome(Status.REJECTED, code, null, null);
        }
    }

    /**
     * @param variantId the variant whose SKU the file name carried; used only when the product has several
     * @param position  1 = main image, 2 and up = the extra gallery images
     * @param hash      SHA-256 (hex) of the bytes
     */
    public record Request(Long productId, Long variantId, int position, MultipartFile file, String hash,
                          ImageImportMode mode, String runId) {
    }

    private final ProductRepository productRepository;
    private final ProductVariantRepository variantRepository;
    private final StorageService storageService;
    private final StorageTransactionSupport storageSupport;

    public ProductImageAttacher(ProductRepository productRepository,
                                ProductVariantRepository variantRepository,
                                StorageService storageService,
                                StorageTransactionSupport storageSupport) {
        this.productRepository = productRepository;
        this.variantRepository = variantRepository;
        this.storageService = storageService;
        this.storageSupport = storageSupport;
    }

    @Transactional
    public Outcome attach(Request request) {
        Product product = productRepository.findByIdForUpdate(request.productId()).orElse(null);
        if (product == null) {
            return Outcome.rejected("NO_MATCH");
        }
        if (product.getArchivedAt() != null || product.getStatus() == ProductStatus.ARCHIVED) {
            return Outcome.rejected("PRODUCT_ARCHIVED");
        }

        List<ProductImage> images = product.getImages();
        List<ProductImage> before = images.stream()
                .filter(i -> !request.runId().equals(i.getImportRunId())).toList();
        List<ProductImage> mine = images.stream()
                .filter(i -> request.runId().equals(i.getImportRunId())).toList();

        // «Already has images» means before this run: a run's own earlier files, arriving in a separate
        // request, must not make the later ones look skippable.
        if (request.mode() == ImageImportMode.SKIP && !before.isEmpty()) {
            return Outcome.skipped("HAS_IMAGES");
        }

        List<ProductImage> removed = new ArrayList<>();
        if (request.mode() == ImageImportMode.REPLACE) {
            removed.addAll(before);
        }
        List<ProductImage> kept = images.stream().filter(i -> !removed.contains(i)).toList();

        if (kept.stream().anyMatch(i -> request.hash().equals(i.getContentHash()))) {
            return Outcome.skipped("DUPLICATE_IMAGE");
        }

        int base = request.mode() == ImageImportMode.ADD
                ? before.stream().mapToInt(ProductImage::getDisplayOrder).max().orElse(-1) + 1
                : 0;
        short order = (short) (base + request.position() - 1);
        if (mine.stream().anyMatch(i -> i.getDisplayOrder() == order)) {
            return Outcome.rejected("DUPLICATE_POSITION");
        }
        if (kept.size() >= MAX_IMAGES_PER_PRODUCT) {
            return Outcome.rejected("LIMIT_REACHED");
        }

        // Past every check that can say no: now the disk. The file is removed again if this does not commit.
        StoredFile stored = storageService.store(request.file(), "products");
        storageSupport.deleteUnlessCommitted(stored.key());

        for (ProductImage old : removed) {
            images.remove(old);
            storageSupport.deleteAfterCommit(old.getUrl());
            storageSupport.deleteAfterCommit(old.getThumbUrl());
        }

        ProductImage image = new ProductImage();
        image.setProduct(product);
        image.setUrl(stored.key());
        image.setDisplayOrder(order);
        image.setContentHash(request.hash());
        image.setImportRunId(request.runId());
        image.setAltTextAr(alt(product, "ar", request.position()));
        image.setAltTextEn(alt(product, "en", request.position()));
        // A product with several variants gets the picture on the variant its file name carried;
        // a single-variant product shares it, which is what the gallery expects.
        if (request.variantId() != null && activeVariants(product) > 1) {
            image.setVariant(variantRepository.getReferenceById(request.variantId()));
        }
        images.add(image);
        chooseMain(images, request.runId());

        productRepository.saveAndFlush(product);
        return Outcome.attached(image.getId(), storageService.urlFor(stored.key()));
    }

    /**
     * Exactly one main image. With none, this run's lowest-numbered image is it, whatever order the files
     * arrived in. If the main image is one this run added, a lower-numbered arrival takes over: SKU.jpg is
     * the main image even when SKU_2.jpg got there first. A main image from before the run stays.
     */
    private void chooseMain(List<ProductImage> images, String runId) {
        ProductImage current = images.stream().filter(ProductImage::isMain).findFirst().orElse(null);
        if (current != null && !runId.equals(current.getImportRunId())) {
            return;
        }
        ProductImage lowest = images.stream()
                .filter(i -> runId.equals(i.getImportRunId()))
                .min(Comparator.comparingInt(ProductImage::getDisplayOrder))
                .orElse(null);
        if (lowest == null || lowest == current) {
            return;
        }
        images.forEach(i -> i.setMain(false));
        lowest.setMain(true);
    }

    private long activeVariants(Product product) {
        return product.getVariants().stream().filter(v -> v.getArchivedAt() == null).count();
    }

    /** The product's own name for the locale, with the place in the gallery after the first image. */
    private String alt(Product product, String locale, int position) {
        ProductTranslation t = product.getTranslations().get(locale);
        if (t == null || t.getName() == null || t.getName().isBlank()) {
            return null;
        }
        String text = position > 1 ? t.getName() + " - " + position : t.getName();
        return text.length() > ALT_MAX ? text.substring(0, ALT_MAX) : text;
    }
}
