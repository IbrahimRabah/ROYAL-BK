package com.velora.api.catalog.repository;

import com.velora.api.catalog.domain.ProductVariant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProductVariantRepository extends JpaRepository<ProductVariant, Long> {

    Optional<ProductVariant> findBySku(String sku);

    Optional<ProductVariant> findByBarcode(String barcode);

    /**
     * All variants of a product with their defining attribute values, so the front
     * end can build the colour/size selectors without another call per variant.
     */
    @EntityGraph(attributePaths = {"attributeValues", "attributeValues.attribute",
            "attributeValues.attributeValue", "attributeValues.attributeValue.translations"})
    List<ProductVariant> findByProductIdAndArchivedAtIsNullOrderByPositionAsc(Long productId);

    boolean existsBySku(String sku);

    /**
     * The variants for a set of SKUs, with their product. The column is case-insensitive
     * (Arabic_CI_AS), so «ch101» finds CH101. Keep the list under SQL Server's 2100-parameter limit.
     */
    @Query("select v from ProductVariant v join fetch v.product where v.sku in :skus")
    List<ProductVariant> findBySkuInWithProduct(@Param("skus") Collection<String> skus);
}
