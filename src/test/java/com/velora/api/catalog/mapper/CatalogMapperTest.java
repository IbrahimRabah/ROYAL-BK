package com.velora.api.catalog.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.velora.api.catalog.domain.Category;
import com.velora.api.catalog.domain.Product;
import com.velora.api.catalog.domain.ProductImage;
import com.velora.api.catalog.domain.ProductTranslation;
import com.velora.api.common.storage.LocalStorageService;
import com.velora.api.common.storage.StorageProperties;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code product_image.url} holds a storage KEY (e.g. {@code products/2026/08/xxx.jpg}),
 * never a full URL — see {@link com.velora.api.catalog.service.admin.ProductImageService}.
 * Every response that surfaces an image must resolve that key through
 * {@code StorageService.urlFor(...)}, or the storefront receives a relative path it
 * cannot render as an <img src>.
 */
class CatalogMapperTest {

    private static final String KEY = "products/2026/08/abc123.jpg";

    private CatalogMapper mapper;

    @BeforeEach
    void setUp(@TempDir Path tempDir) {
        StorageProperties properties = new StorageProperties();
        properties.setLocalPath(tempDir.toString());
        mapper = new CatalogMapper(new LocalStorageService(properties));
    }

    @Test
    @DisplayName("Product list card resolves the stored key to a full, openable URL")
    void summaryImageUrlIsAbsolute() {
        Product product = productWithMainImage(KEY);

        var summary = mapper.toSummary(product, "ar");

        assertThat(summary.imageUrl()).startsWith("http");
        assertThat(summary.imageUrl()).isEqualTo("http://localhost:8080/uploads/" + KEY);
    }

    @Test
    @DisplayName("Product detail / variant gallery image resolves the stored key too")
    void detailImageUrlIsAbsolute() {
        ProductImage image = image(KEY, true);

        var response = mapper.toImage(image, "ar");

        assertThat(response.url()).startsWith("http");
        assertThat(response.url()).isEqualTo("http://localhost:8080/uploads/" + KEY);
    }

    @Test
    @DisplayName("Single-image product has no hover image")
    void singleImageProductHasNoHoverImage() {
        Product product = productWithMainImage(KEY);

        var summary = mapper.toSummary(product, "ar");

        assertThat(summary.hoverImageUrl()).isNull();
    }

    @Test
    @DisplayName("Second image by displayOrder resolves as the hover image")
    void hoverImageIsTheSecondByDisplayOrder() {
        String hoverKey = "products/2026/08/hover456.jpg";
        Product product = productWithMainImage(KEY);
        product.getImages().add(image(hoverKey, false));

        var summary = mapper.toSummary(product, "ar");

        assertThat(summary.hoverImageUrl()).isEqualTo("http://localhost:8080/uploads/" + hoverKey);
    }

    @Test
    @DisplayName("A third image is never the hover image")
    void aThirdImageIsIgnored() {
        Product product = productWithMainImage(KEY);
        product.getImages().add(image("products/2026/08/hover456.jpg", false));
        product.getImages().add(image("products/2026/08/third789.jpg", false));

        var summary = mapper.toSummary(product, "ar");

        assertThat(summary.hoverImageUrl()).contains("hover456.jpg");
    }

    @Test
    @DisplayName("Category tree node resolves stored image/banner keys to full URLs")
    void treeNodeImageUrlsAreAbsolute() {
        Category category = new Category();
        category.setSlug("mens-watches");
        category.setImageUrl("categories/2026/09/card123.jpg");
        category.setBannerUrl("categories/2026/09/banner456.jpg");

        var node = mapper.toTreeNode(category, "ar", List.of());

        assertThat(node.imageUrl())
                .isEqualTo("http://localhost:8080/uploads/categories/2026/09/card123.jpg");
        assertThat(node.bannerUrl())
                .isEqualTo("http://localhost:8080/uploads/categories/2026/09/banner456.jpg");
    }

    @Test
    @DisplayName("Category tree node has null image/banner when no key is stored")
    void treeNodeImageUrlsAreNullWhenNoImageUploaded() {
        Category category = new Category();
        category.setSlug("new-category");

        var node = mapper.toTreeNode(category, "ar", List.of());

        assertThat(node.imageUrl()).isNull();
        assertThat(node.bannerUrl()).isNull();
    }

    private Product productWithMainImage(String key) {
        Product product = new Product();
        product.setSlug("classic-watch");

        ProductTranslation translation = new ProductTranslation();
        translation.attachTo(product, "ar");
        translation.setName("ساعة كلاسيك");
        product.getTranslations().put("ar", translation);

        product.getImages().add(image(key, true));
        return product;
    }

    private ProductImage image(String key, boolean main) {
        ProductImage image = new ProductImage();
        image.setUrl(key);
        image.setMain(main);
        return image;
    }
}
