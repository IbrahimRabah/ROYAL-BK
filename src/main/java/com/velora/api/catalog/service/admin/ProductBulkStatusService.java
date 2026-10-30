package com.velora.api.catalog.service.admin;

import com.velora.api.catalog.dto.admin.ProductAdminResponse;
import com.velora.api.catalog.dto.admin.ProductBulkStatusRequest;
import com.velora.api.catalog.dto.admin.ProductBulkStatusResponse;
import com.velora.api.catalog.dto.admin.ProductBulkStatusResponse.Skipped;
import com.velora.api.common.exception.BusinessException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Publish or unpublish many products in one request, from the product list.
 *
 * <p>Deliberately NOT one transaction: each product goes through {@link ProductAdminService} in its own
 * transaction, so a product that cannot go live (a ready-made one with no variants, one with no Arabic
 * name) is reported and skipped while the others still change. All-or-nothing would make one bad draft
 * hold back a hundred good ones, and the person can see exactly which were left and why.
 *
 * <p>Only a DRAFT is published and only an ACTIVE product is unpublished: publishing an archived product
 * would quietly bring it back, which is a different action.
 */
@Service
public class ProductBulkStatusService {

    private static final Logger log = LoggerFactory.getLogger(ProductBulkStatusService.class);

    static final String NOT_DRAFT = "NOT_DRAFT";
    static final String NOT_ACTIVE = "NOT_ACTIVE";

    private final ProductAdminService productService;

    public ProductBulkStatusService(ProductAdminService productService) {
        this.productService = productService;
    }

    public ProductBulkStatusResponse apply(ProductBulkStatusRequest request) {
        List<Long> ids = new ArrayList<>(new LinkedHashSet<>(request.ids()));
        List<Long> succeeded = new ArrayList<>();
        List<Skipped> skipped = new ArrayList<>();
        boolean publish = request.action() == ProductBulkStatusRequest.Action.PUBLISH;

        for (Long id : ids) {
            ProductAdminResponse product;
            try {
                product = productService.get(id);
            } catch (BusinessException e) {
                skipped.add(new Skipped(id, null, e.getErrorCode().name()));
                continue;
            }
            String name = product.nameAr() != null ? product.nameAr() : product.nameEn();

            String wanted = publish ? "DRAFT" : "ACTIVE";
            if (!wanted.equals(product.status())) {
                skipped.add(new Skipped(id, name, publish ? NOT_DRAFT : NOT_ACTIVE));
                continue;
            }
            try {
                if (publish) {
                    productService.publish(id);
                } else {
                    productService.unpublish(id);
                }
                succeeded.add(id);
            } catch (BusinessException e) {
                skipped.add(new Skipped(id, name, e.getErrorCode().name()));
            }
        }

        log.info("Bulk {} of {} products: {} done, {} skipped", request.action(), ids.size(),
                succeeded.size(), skipped.size());
        return new ProductBulkStatusResponse(request.action().name(), ids.size(), succeeded, skipped);
    }
}
