package com.velora.api.common.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.velora.api.common.exception.BusinessException;
import com.velora.api.common.exception.ErrorCode;
import com.velora.api.testsupport.TestImages;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

/**
 * Uploads are checked against what the file really is, not what the sender says it is.
 * The filename and the declared type are both written by the caller, and the files are
 * served from the application's own origin.
 */
class LocalStorageServiceTest {

    @TempDir Path tempDir;

    private LocalStorageService storage;

    @BeforeEach
    void setUp() {
        StorageProperties properties = new StorageProperties();
        properties.setLocalPath(tempDir.toString());
        properties.setPublicUrlPrefix("http://localhost/uploads");
        storage = new LocalStorageService(properties);
    }

    @Test
    @DisplayName("A real image is stored, with the detected type")
    void storesARealImage() {
        StoredFile stored = storage.store(
                new MockMultipartFile("file", "photo.jpg", "image/jpeg", TestImages.jpeg()),
                "products");

        assertThat(stored.contentType()).isEqualTo("image/jpeg");
        assertThat(stored.key()).startsWith("products/").endsWith(".jpg");
        assertThat(storage.exists(stored.key())).isTrue();
    }

    @Test
    @DisplayName("HTML sent as image/png is refused, not stored")
    void htmlLabelledAsPngIsRefused() {
        assertThatThrownBy(() -> storage.store(
                new MockMultipartFile("file", "x.png", "image/png", TestImages.html()),
                "custom-requests"))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));
    }

    @Test
    @DisplayName("A text file is refused on its declared type alone")
    void wrongDeclaredTypeIsRefused() {
        assertThatThrownBy(() -> storage.store(
                new MockMultipartFile("file", "notes.txt", "text/plain", "hello".getBytes()),
                "custom-requests"))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("The stored extension comes from the detected type, never from the filename")
    void filenameCannotChooseTheExtension() {
        StoredFile stored = storage.store(
                new MockMultipartFile("file", "evil.html", "image/png", TestImages.png()),
                "custom-requests");

        assertThat(stored.key()).endsWith(".png").doesNotContain("html");
        assertThat(stored.contentType()).isEqualTo("image/png");
    }

    @Test
    @DisplayName("An image whose declared type is wrong is stored as what it actually is")
    void mislabelledImageIsStoredAsItsRealType() {
        StoredFile stored = storage.store(
                new MockMultipartFile("file", "a.jpg", "image/jpeg", TestImages.webp()),
                "custom-requests");

        assertThat(stored.contentType()).isEqualTo("image/webp");
        assertThat(stored.key()).endsWith(".webp");
    }

    @Test
    @DisplayName("An empty file and an oversized file are refused")
    void emptyAndOversizedAreRefused() {
        assertThatThrownBy(() -> storage.store(
                new MockMultipartFile("file", "a.png", "image/png", new byte[0]), "x"))
                .isInstanceOf(BusinessException.class);

        byte[] tooBig = TestImages.padded(TestImages.png(), 5 * 1024 * 1024 + 1);
        assertThatThrownBy(() -> storage.store(
                new MockMultipartFile("file", "big.png", "image/png", tooBig), "x"))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("A file of exactly the maximum size is accepted")
    void maximumSizeIsAccepted() {
        byte[] exactly = TestImages.padded(TestImages.png(), 5 * 1024 * 1024);

        assertThat(storage.store(
                new MockMultipartFile("file", "max.png", "image/png", exactly), "x").sizeBytes())
                .isEqualTo(5L * 1024 * 1024);
    }
}
