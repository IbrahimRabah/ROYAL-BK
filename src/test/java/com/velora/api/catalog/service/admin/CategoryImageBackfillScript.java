package com.velora.api.catalog.service.admin;

import com.velora.api.catalog.domain.Category;
import com.velora.api.catalog.domain.CategoryImageType;
import com.velora.api.catalog.repository.CategoryRepository;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;

/**
 * ============================================================================
 *  ONE-OFF BACKFILL SCRIPT — NOT A TEST, NOT A FLYWAY MIGRATION.
 * ============================================================================
 *
 * <p><b>What it does:</b> copies the 12 category images that already exist in the
 * Angular project's {@code assets} folder (9 banners keyed by
 * {parentSlug}/{scopeSlug}, 3 parent-only card images) into VELORA's own upload
 * store, going through {@link TaxonomyAdminService#uploadCategoryImage} exactly as
 * a real admin-panel upload would — same validation, same storage key generation.
 * It exists to replace the manual "upload 18 images through the admin panel" step.
 *
 * <p><b>Why it lives in {@code src/test} instead of being deleted after first use:</b>
 * the same 18-image manual upload will be needed again if the images move to
 * another server, or if the database has to be rebuilt from scratch before
 * handover to the client. Keeping the script means that second time is a single
 * command instead of another 18 manual uploads. Delete this file once the client
 * handover is complete and no further rebuilds are expected — it has no reason to
 * exist after that.
 *
 * <p><b>Why it is not part of the normal test suite:</b> it mutates the real
 * database and copies real files — it must never run as a side effect of
 * {@code mvn test} / CI. It is deliberately named without a {@code Test}/
 * {@code Tests}/{@code IT} suffix so Surefire's default include pattern skips it
 * during a normal {@code mvn test} or {@code mvn clean test} run (verified: a full
 * suite run does not touch it). It only runs when invoked by its exact name:
 *
 * <pre>
 * mvnw test -Dtest=CategoryImageBackfillScript#run -DfailIfNoTests=false
 * </pre>
 *
 * <p>If the frontend checkout is not at
 * {@code D:/ibrahim_watches/VELORA_FRONT/VELORA/src/assets/images}, override it:
 *
 * <pre>
 * mvnw test -Dtest=CategoryImageBackfillScript#run -DfailIfNoTests=false ^
 *     -Dfrontend.assets.dir="C:/path/to/VELORA/src/assets/images"
 * </pre>
 *
 * <p><b>Safe to run more than once</b> (already verified): any category that
 * already has an image of the given type is skipped, never overwritten, so
 * re-running after a partial run or on a rebuilt database does not create
 * duplicate files or double-upload anything already in place.
 */
@SpringBootTest
class CategoryImageBackfillScript {

    private static final Path ASSETS_ROOT = Path.of(
            System.getProperty("frontend.assets.dir",
                    "D:/ibrahim_watches/VELORA_FRONT/VELORA/src/assets/images"));

    /**
     * (category slug, image type, path under ASSETS_ROOT).
     *
     * <p>The {@code -2} suffix on {@code women-wallets-2} / {@code women-perfumes-2}
     * is NOT a naming-scheme leftover to strip — the frontend's own folder carries
     * the exact same suffix as the slug, confirmed on disk before writing this list.
     */
    private static final List<Seed> SEEDS = List.of(
            new Seed("watches", CategoryImageType.BANNER, "products/watches/all-watches/banner.png"),
            new Seed("men-watches", CategoryImageType.BANNER, "products/watches/men-watches/banner.png"),
            new Seed("women-watches", CategoryImageType.BANNER, "products/watches/women-watches/banner.png"),
            new Seed("wallets", CategoryImageType.BANNER, "products/wallets/all-wallets/banner.png"),
            new Seed("men-wallets", CategoryImageType.BANNER, "products/wallets/men-wallets/banner.png"),
            new Seed("women-wallets-2", CategoryImageType.BANNER, "products/wallets/women-wallets-2/banner.png"),
            new Seed("perfumes", CategoryImageType.BANNER, "products/perfumes/all-perfumes/banner.png"),
            new Seed("men-perfumes", CategoryImageType.BANNER, "products/perfumes/men-perfumes/banner.png"),
            new Seed("women-perfumes-2", CategoryImageType.BANNER, "products/perfumes/women-perfumes-2/banner.png"),
            new Seed("watches", CategoryImageType.CARD, "categories/watch.png"),
            new Seed("wallets", CategoryImageType.CARD, "categories/wallet.png"),
            new Seed("perfumes", CategoryImageType.CARD, "categories/perfume.png")
    );

    @Autowired private TaxonomyAdminService taxonomyService;
    @Autowired private CategoryRepository categoryRepository;

    @Test
    void run() throws IOException {
        List<Category> allCategories = categoryRepository.findAllByOrderByDisplayOrderAscIdAsc();
        List<Result> results = new ArrayList<>();

        for (Seed seed : SEEDS) {
            results.add(process(seed, allCategories));
        }

        printReport(results);
    }

    private Result process(Seed seed, List<Category> allCategories) throws IOException {
        Category category = allCategories.stream()
                .filter(c -> c.getSlug().equals(seed.categorySlug()))
                .findFirst()
                .orElse(null);

        if (category == null) {
            return new Result(seed, Outcome.CATEGORY_NOT_FOUND,
                    "No category with slug '" + seed.categorySlug() + "'");
        }

        boolean alreadyHasImage = seed.imageType() == CategoryImageType.CARD
                ? category.getImageUrl() != null
                : category.getBannerUrl() != null;
        if (alreadyHasImage) {
            return new Result(seed, Outcome.SKIPPED_HAS_IMAGE,
                    "Category %d already has a %s image".formatted(category.getId(), seed.imageType()));
        }

        Path file = ASSETS_ROOT.resolve(seed.relativePath());
        if (!Files.isRegularFile(file)) {
            return new Result(seed, Outcome.FILE_NOT_FOUND, file.toString());
        }

        try {
            byte[] content = Files.readAllBytes(file);
            MockMultipartFile upload = new MockMultipartFile(
                    "file", file.getFileName().toString(), "image/png", content);

            taxonomyService.uploadCategoryImage(category.getId(), seed.imageType(), upload);
            return new Result(seed, Outcome.UPLOADED, "from " + file);
        } catch (RuntimeException ex) {
            return new Result(seed, Outcome.FAILED, ex.getMessage());
        }
    }

    private void printReport(List<Result> results) {
        long uploaded = results.stream().filter(r -> r.outcome() == Outcome.UPLOADED).count();
        long skipped = results.stream().filter(r -> r.outcome() == Outcome.SKIPPED_HAS_IMAGE).count();
        long notFound = results.stream().filter(r -> r.outcome() == Outcome.FILE_NOT_FOUND).count();
        long categoryMissing = results.stream()
                .filter(r -> r.outcome() == Outcome.CATEGORY_NOT_FOUND).count();
        long failed = results.stream().filter(r -> r.outcome() == Outcome.FAILED).count();

        System.out.println();
        System.out.println("==================== Category image backfill report ====================");
        for (Result r : results) {
            System.out.printf("[%-19s] %-16s %-7s  %s%n",
                    r.outcome(), r.seed().categorySlug(), r.seed().imageType(), r.detail());
        }
        System.out.println("---------------------------------------------------------------------------");
        System.out.printf("Uploaded: %d   Skipped (already had image): %d   "
                        + "File not found: %d   Category not found: %d   Failed: %d%n",
                uploaded, skipped, notFound, categoryMissing, failed);
        System.out.println("===========================================================================");
    }

    private record Seed(String categorySlug, CategoryImageType imageType, String relativePath) {
    }

    private enum Outcome {
        UPLOADED, SKIPPED_HAS_IMAGE, FILE_NOT_FOUND, CATEGORY_NOT_FOUND, FAILED
    }

    private record Result(Seed seed, Outcome outcome, String detail) {
    }
}
