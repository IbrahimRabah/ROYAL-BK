package com.velora.api.catalog.service.admin;

import com.velora.api.catalog.domain.Product;
import com.velora.api.catalog.domain.ProductImage;
import com.velora.api.catalog.dto.admin.ImageResponse;
import com.velora.api.catalog.dto.admin.ImageUpdateRequest;
import com.velora.api.catalog.repository.ProductRepository;
import com.velora.api.catalog.repository.ProductVariantRepository;
import com.velora.api.common.exception.BusinessException;
import com.velora.api.common.exception.ErrorCode;
import com.velora.api.common.storage.StorageService;
import com.velora.api.common.storage.StorageTransactionSupport;
import com.velora.api.common.storage.StoredFile;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

/**
 * Product image upload and metadata.
 *
 * <p>The database stores the storage KEY, not the URL. The URL contains the host and
 * CDN prefix and both change; the key does not. URLs are built at read time.
 */
@Service
public class ProductImageService {

    private static final Logger log = LoggerFactory.getLogger(ProductImageService.class);
    private static final int MAX_IMAGES_PER_PRODUCT = 20;

    private final ProductRepository productRepository;
    private final ProductVariantRepository variantRepository;
    private final StorageService storageService;
    private final StorageTransactionSupport storageSupport;

    public ProductImageService(ProductRepository productRepository,
                               ProductVariantRepository variantRepository,
                               StorageService storageService,
                               StorageTransactionSupport storageSupport) {
        this.productRepository = productRepository;
        this.variantRepository = variantRepository;
        this.storageService = storageService;
        this.storageSupport = storageSupport;
    }

    @Transactional
    public ImageResponse upload(Long productId, MultipartFile file, Long variantId) {
        // Locked until commit: two uploads for one product are checked one after the other, so the
        // limit and «the first image is the main one» are decided on what is really stored.
        Product product = productRepository.findByIdForUpdate(productId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PRODUCT_NOT_FOUND));

        if (product.getImages().size() >= MAX_IMAGES_PER_PRODUCT) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "A product can have at most %d images".formatted(MAX_IMAGES_PER_PRODUCT));
        }

        StoredFile stored = storageService.store(file, "products");
        // The file is on disk before the row exists: if this transaction does not commit, it must not stay.
        storageSupport.deleteUnlessCommitted(stored.key());

        ProductImage image = new ProductImage();
        image.setProduct(product);
        image.setUrl(stored.key());
        image.setDisplayOrder((short) product.getImages().size());
        // The first image uploaded becomes the main one automatically.
        image.setMain(product.getImages().isEmpty());

        if (variantId != null) {
            image.setVariant(variantRepository.findById(variantId)
                    .orElseThrow(() -> new BusinessException(ErrorCode.VARIANT_NOT_FOUND)));
        }

        product.getImages().add(image);
        productRepository.save(product);

        log.info("Uploaded image {} for product id={}", stored.key(), productId);
        return toResponse(image);
    }

    @Transactional
    public ImageResponse update(Long productId, Long imageId, ImageUpdateRequest request) {
        Product product = load(productId);
        ProductImage image = findImage(product, imageId);

        if (request.altTextAr() != null) {
            image.setAltTextAr(request.altTextAr());
        }
        if (request.altTextEn() != null) {
            image.setAltTextEn(request.altTextEn());
        }
        if (request.displayOrder() != null) {
            image.setDisplayOrder(request.displayOrder().shortValue());
        }
        if (request.variantId() != null) {
            image.setVariant(variantRepository.findById(request.variantId())
                    .orElseThrow(() -> new BusinessException(ErrorCode.VARIANT_NOT_FOUND)));
        }
        if (Boolean.TRUE.equals(request.main())) {
            // Exactly one main image per product.
            product.getImages().forEach(i -> i.setMain(false));
            image.setMain(true);
        }

        productRepository.save(product);
        return toResponse(image);
    }

    @Transactional
    public void delete(Long productId, Long imageId) {
        Product product = load(productId);
        ProductImage image = findImage(product, imageId);
        String key = image.getUrl();

        boolean wasMain = image.isMain();
        product.getImages().remove(image);

        // Never leave a product without a main image.
        if (wasMain && !product.getImages().isEmpty()) {
            product.getImages().get(0).setMain(true);
        }

        productRepository.save(product);
        storageService.delete(key);
        log.info("Deleted image {} from product id={}", key, productId);
    }

    @Transactional(readOnly = true)
    public List<ImageResponse> list(Long productId) {
        return load(productId).getImages().stream().map(this::toResponse).toList();
    }

    private Product load(Long id) {
        return productRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.PRODUCT_NOT_FOUND));
    }

    private ProductImage findImage(Product product, Long imageId) {
        return product.getImages().stream()
                .filter(i -> imageId.equals(i.getId()))
                .findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND,
                        "Image not found on this product"));
    }

    private ImageResponse toResponse(ProductImage image) {
        return new ImageResponse(
                image.getId(),
                image.getUrl(),
                storageService.urlFor(image.getUrl()),
                image.getThumbUrl() == null ? null : storageService.urlFor(image.getThumbUrl()),
                image.getAltTextAr(),
                image.getAltTextEn(),
                image.isMain(),
                image.getDisplayOrder(),
                image.getVariant() == null ? null : image.getVariant().getId());
    }
}
