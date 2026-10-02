package com.velora.api.common.storage;

import com.velora.api.common.exception.BusinessException;
import com.velora.api.common.exception.ErrorCode;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Locale;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/**
 * Stores files on the local filesystem.
 *
 * <p>Fine for development and a single-server deployment. It does NOT survive a
 * container rebuild and does not work behind more than one instance — which is
 * exactly why every caller talks to {@link StorageService} instead of this class.
 * Swapping in an R2 implementation later touches no other file.
 */
@Service
public class LocalStorageService implements StorageService {

    private static final Logger log = LoggerFactory.getLogger(LocalStorageService.class);

    private final StorageProperties properties;
    private final Path root;

    public LocalStorageService(StorageProperties properties) {
        this.properties = properties;
        this.root = Paths.get(properties.getLocalPath()).toAbsolutePath().normalize();
        try {
            Files.createDirectories(root);
            log.info("Local file storage rooted at {}", root);
        } catch (IOException ex) {
            throw new IllegalStateException("Cannot create storage directory: " + root, ex);
        }
    }

    @Override
    public StoredFile store(MultipartFile file, String folder) {
        // What the bytes are, not what the sender says they are: the declared type and
        // the filename are both under the sender's control, and these files are served
        // from the application's own origin.
        String detectedType = validate(file);

        String extension = extensionFor(detectedType);
        LocalDate today = LocalDate.now();

        // Date folders keep any single directory from growing to tens of thousands
        // of files, which makes both the filesystem and a later migration slow.
        String key = "%s/%d/%02d/%s%s".formatted(
                sanitizeFolder(folder), today.getYear(), today.getMonthValue(),
                UUID.randomUUID().toString().replace("-", ""), extension);

        Path target = resolveSafely(key);

        try {
            Files.createDirectories(target.getParent());
            try (InputStream in = file.getInputStream()) {
                Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException ex) {
            log.error("Failed to store file {}", key, ex);
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "Could not save the file");
        }

        return new StoredFile(key, urlFor(key), file.getSize(), detectedType);
    }

    @Override
    public String storeBytes(byte[] content, String folder, String filename,
                             String contentType) {
        LocalDate today = LocalDate.now();

        // Generated documents keep their own name — an invoice PDF should be findable
        // on disk by its invoice number, not by a random UUID.
        String key = "%s/%d/%02d/%s".formatted(
                sanitizeFolder(folder), today.getYear(), today.getMonthValue(),
                sanitizeFilename(filename));

        Path target = resolveSafely(key);

        try {
            Files.createDirectories(target.getParent());
            Files.write(target, content);
        } catch (IOException ex) {
            log.error("Failed to store generated file {}", key, ex);
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "Could not save the file");
        }

        log.debug("Stored generated file {} ({} bytes)", key, content.length);
        return key;
    }

    @Override
    public void delete(String key) {
        if (key == null || key.isBlank()) {
            return;
        }
        try {
            Files.deleteIfExists(resolveSafely(key));
        } catch (IOException ex) {
            // A file that cannot be deleted must not fail the request that owns it.
            log.warn("Could not delete stored file {}: {}", key, ex.getMessage());
        }
    }

    @Override
    public String urlFor(String key) {
        if (key == null || key.isBlank()) {
            return null;
        }
        String prefix = properties.getPublicUrlPrefix();
        return prefix.endsWith("/") ? prefix + key : prefix + "/" + key;
    }

    @Override
    public boolean exists(String key) {
        return key != null && Files.exists(resolveSafely(key));
    }

    // ------------------------------------------------------------------ internal

    /**
     * @return the real content type, taken from the file's first bytes
     */
    private String validate(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "The file is empty");
        }
        if (file.getSize() > properties.getMaxFileSizeBytes()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "Maximum file size is %d MB".formatted(
                            properties.getMaxFileSizeBytes() / (1024 * 1024)));
        }
        String contentType = file.getContentType();
        boolean allowed = contentType != null
                && Arrays.stream(properties.getAllowedContentTypes())
                        .anyMatch(t -> t.equalsIgnoreCase(contentType));
        if (!allowed) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "Only JPEG, PNG, WebP and AVIF images are accepted");
        }

        // The declared type passed, but it is only a claim. Check the bytes.
        byte[] head;
        try (InputStream in = file.getInputStream()) {
            head = in.readNBytes(ImageSniffer.HEAD_BYTES);
        } catch (IOException ex) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "The file could not be read");
        }
        String detected = ImageSniffer.detect(head).orElseThrow(() ->
                new BusinessException(ErrorCode.VALIDATION_FAILED,
                        "The file is not a valid JPEG, PNG, WebP or AVIF image"));
        boolean detectedAllowed = Arrays.stream(properties.getAllowedContentTypes())
                .anyMatch(t -> t.equalsIgnoreCase(detected));
        if (!detectedAllowed) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "Only JPEG, PNG, WebP and AVIF images are accepted");
        }
        return detected;
    }

    /**
     * Resolves a key under the storage root and refuses to escape it.
     *
     * <p>Without this check a key containing {@code ../../} would let a caller write
     * anywhere on the disk — the classic path traversal.
     */
    private Path resolveSafely(String key) {
        Path resolved = root.resolve(key).normalize();
        if (!resolved.startsWith(root)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "Invalid file path");
        }
        return resolved;
    }

    private String sanitizeFolder(String folder) {
        if (folder == null || folder.isBlank()) {
            return "misc";
        }
        return folder.toLowerCase(Locale.ENGLISH).replaceAll("[^a-z0-9-]", "");
    }

    private String sanitizeFilename(String filename) {
        if (filename == null || filename.isBlank()) {
            return UUID.randomUUID().toString().replace("-", "");
        }
        return filename.replaceAll("[^A-Za-z0-9._-]", "-");
    }

    /**
     * The extension comes from the detected type, never from the uploaded filename: a
     * name like {@code evil.html} would otherwise be stored as-is and served from the
     * application's own origin.
     */
    private String extensionFor(String detectedContentType) {
        return switch (detectedContentType) {
            case "image/png" -> ".png";
            case "image/webp" -> ".webp";
            case "image/avif" -> ".avif";
            default -> ".jpg";
        };
    }
}
