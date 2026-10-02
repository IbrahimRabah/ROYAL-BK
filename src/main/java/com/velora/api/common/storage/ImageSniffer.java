package com.velora.api.common.storage;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * Identifies an image by its first bytes, not by what the client says it is.
 *
 * <p>The {@code Content-Type} of an upload and its filename are both written by the
 * sender. Trusting either lets anyone store an HTML page or a script under an image's
 * name, on a path the storefront serves from its own origin. The magic bytes are the one
 * thing a browser's image decoder would also rely on.
 *
 * <p>Only the four formats the storefront accepts are recognised; everything else —
 * including GIF, SVG and HEIC — returns empty.
 */
public final class ImageSniffer {

    /** How much of the file {@link #detect} needs to see. */
    public static final int HEAD_BYTES = 64;

    private static final byte[] PNG = {
            (byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A
    };

    private ImageSniffer() {
        // utility class
    }

    /** @return the content type — {@code image/jpeg|png|webp|avif} — or empty if none match */
    public static Optional<String> detect(byte[] head) {
        if (head == null) {
            return Optional.empty();
        }
        if (isJpeg(head)) {
            return Optional.of("image/jpeg");
        }
        if (startsWith(head, PNG)) {
            return Optional.of("image/png");
        }
        if (isWebp(head)) {
            return Optional.of("image/webp");
        }
        if (isAvif(head)) {
            return Optional.of("image/avif");
        }
        return Optional.empty();
    }

    private static boolean isJpeg(byte[] head) {
        return head.length >= 3
                && head[0] == (byte) 0xFF && head[1] == (byte) 0xD8 && head[2] == (byte) 0xFF;
    }

    /** {@code RIFF <size> WEBP}. */
    private static boolean isWebp(byte[] head) {
        return head.length >= 12
                && ascii(head, 0, 4).equals("RIFF")
                && ascii(head, 8, 4).equals("WEBP");
    }

    /**
     * An ISO base media file: a {@code ftyp} box at offset 4 whose major or compatible
     * brands include {@code avif} (still) or {@code avis} (sequence).
     */
    private static boolean isAvif(byte[] head) {
        if (head.length < 12 || !ascii(head, 4, 4).equals("ftyp")) {
            return false;
        }
        long boxSize = ((head[0] & 0xFFL) << 24) | ((head[1] & 0xFFL) << 16)
                | ((head[2] & 0xFFL) << 8) | (head[3] & 0xFFL);
        int end = (int) Math.min(head.length, Math.max(16, boxSize));

        // Major brand at 8, then minor version at 12, then compatible brands from 16.
        if (isAvifBrand(ascii(head, 8, 4))) {
            return true;
        }
        for (int offset = 16; offset + 4 <= end; offset += 4) {
            if (isAvifBrand(ascii(head, offset, 4))) {
                return true;
            }
        }
        return false;
    }

    private static boolean isAvifBrand(String brand) {
        return brand.equals("avif") || brand.equals("avis");
    }

    private static boolean startsWith(byte[] head, byte[] signature) {
        if (head.length < signature.length) {
            return false;
        }
        for (int i = 0; i < signature.length; i++) {
            if (head[i] != signature[i]) {
                return false;
            }
        }
        return true;
    }

    private static String ascii(byte[] bytes, int offset, int length) {
        return new String(bytes, offset, length, StandardCharsets.ISO_8859_1);
    }
}
