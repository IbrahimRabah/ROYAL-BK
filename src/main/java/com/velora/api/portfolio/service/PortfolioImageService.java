package com.velora.api.portfolio.service;

import com.velora.api.common.exception.BusinessException;
import com.velora.api.common.exception.ErrorCode;
import com.velora.api.common.storage.StorageService;
import com.velora.api.common.storage.StoredFile;
import com.velora.api.portfolio.domain.PortfolioImage;
import com.velora.api.portfolio.domain.PortfolioItem;
import com.velora.api.portfolio.dto.admin.PortfolioAdminResponse.PortfolioImageAdminResponse;
import com.velora.api.portfolio.dto.admin.PortfolioImageUpdateRequest;
import com.velora.api.portfolio.mapper.PortfolioMapper;
import com.velora.api.portfolio.repository.PortfolioItemRepository;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

/**
 * Portfolio images, under exactly the rules of product images: every upload goes through
 * {@link StorageService#store}, which identifies the file by its real bytes (JPEG, PNG, WebP or
 * AVIF) and names it from the detected type, never from the client filename or Content-Type.
 *
 * <p>Archiving an item leaves its files alone; only deleting one image removes a file.
 */
@Service
public class PortfolioImageService {

    private static final Logger log = LoggerFactory.getLogger(PortfolioImageService.class);
    static final int MAX_IMAGES_PER_ITEM = 20;
    static final String FOLDER = "portfolio";

    private final PortfolioItemRepository repository;
    private final StorageService storageService;
    private final PortfolioMapper mapper;

    public PortfolioImageService(PortfolioItemRepository repository, StorageService storageService,
                                 PortfolioMapper mapper) {
        this.repository = repository;
        this.storageService = storageService;
        this.mapper = mapper;
    }

    @Transactional
    public PortfolioImageAdminResponse upload(Long itemId, MultipartFile file) {
        PortfolioItem item = loadLive(itemId);

        if (item.getImages().size() >= MAX_IMAGES_PER_ITEM) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "A portfolio item can have at most %d images".formatted(MAX_IMAGES_PER_ITEM));
        }

        StoredFile stored = storageService.store(file, FOLDER);

        PortfolioImage image = new PortfolioImage();
        image.setItem(item);
        image.setUrl(stored.key());
        image.setDisplayOrder((short) item.getImages().size());
        image.setMain(item.getImages().isEmpty());
        item.getImages().add(image);
        repository.saveAndFlush(item);

        log.info("Uploaded portfolio image {} for item id={}", stored.key(), itemId);
        return mapper.toAdminImage(image);
    }

    @Transactional
    public PortfolioImageAdminResponse update(Long itemId, Long imageId,
                                              PortfolioImageUpdateRequest request) {
        PortfolioItem item = loadLive(itemId);
        PortfolioImage image = find(item, imageId);

        if (request.altTextAr() != null) {
            image.setAltTextAr(request.altTextAr());
        }
        if (request.altTextEn() != null) {
            image.setAltTextEn(request.altTextEn());
        }
        if (request.displayOrder() != null) {
            image.setDisplayOrder(request.displayOrder().shortValue());
        }
        if (Boolean.TRUE.equals(request.main())) {
            item.getImages().forEach(i -> i.setMain(false));
            image.setMain(true);
        }
        repository.saveAndFlush(item);
        return mapper.toAdminImage(image);
    }

    @Transactional
    public void delete(Long itemId, Long imageId) {
        PortfolioItem item = loadLive(itemId);
        PortfolioImage image = find(item, imageId);
        String key = image.getUrl();

        boolean wasMain = image.isMain();
        item.getImages().remove(image);
        if (wasMain && !item.getImages().isEmpty()) {
            item.getImages().get(0).setMain(true);
        }
        repository.saveAndFlush(item);
        storageService.delete(key);
        log.info("Deleted portfolio image {} from item id={}", key, itemId);
    }

    @Transactional(readOnly = true)
    public List<PortfolioImageAdminResponse> list(Long itemId) {
        PortfolioItem item = repository.findById(itemId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PORTFOLIO_ITEM_NOT_FOUND));
        return item.getImages().stream().map(mapper::toAdminImage).toList();
    }

    /** An archived item is frozen: restore it first. */
    private PortfolioItem loadLive(Long itemId) {
        PortfolioItem item = repository.findById(itemId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PORTFOLIO_ITEM_NOT_FOUND));
        if (item.isArchived()) {
            throw new BusinessException(ErrorCode.PORTFOLIO_ITEM_ARCHIVED,
                    "Restore this item before changing its images");
        }
        return item;
    }

    private PortfolioImage find(PortfolioItem item, Long imageId) {
        return item.getImages().stream()
                .filter(i -> imageId.equals(i.getId()))
                .findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND,
                        "Image not found on this item"));
    }
}
