package com.velora.api.testsupport;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Smallest byte sequences that are recognisably each image format — a real header
 * followed by padding. {@code StorageService} now checks a file's actual bytes, so a test
 * that uploads {@code "fake-image-bytes"} labelled {@code image/jpeg} is correctly
 * refused. These are not decodable pictures, only what the format sniffer looks at.
 */
public final class TestImages {

    private TestImages() {
        // utility class
    }

    public static byte[] jpeg() {
        return pad(new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0}, 128);
    }

    public static byte[] png() {
        return pad(new byte[] {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A}, 128);
    }

    public static byte[] webp() {
        byte[] head = new byte[12];
        System.arraycopy("RIFF".getBytes(StandardCharsets.US_ASCII), 0, head, 0, 4);
        System.arraycopy("WEBP".getBytes(StandardCharsets.US_ASCII), 0, head, 8, 4);
        return pad(head, 128);
    }

    public static byte[] avif() {
        byte[] head = new byte[28];
        head[3] = 28;                                            // box size
        System.arraycopy("ftyp".getBytes(StandardCharsets.US_ASCII), 0, head, 4, 4);
        System.arraycopy("avif".getBytes(StandardCharsets.US_ASCII), 0, head, 8, 4);
        System.arraycopy("mif1".getBytes(StandardCharsets.US_ASCII), 0, head, 16, 4);
        return pad(head, 128);
    }

    /** What an attacker would upload under an image's name. */
    public static byte[] html() {
        return "<html><script>alert(document.cookie)</script></html>"
                .getBytes(StandardCharsets.UTF_8);
    }

    /** The same bytes, grown to {@code size} — for the size-limit tests. */
    public static byte[] padded(byte[] head, int size) {
        return pad(head, size);
    }

    private static byte[] pad(byte[] head, int size) {
        return Arrays.copyOf(head, Math.max(size, head.length));
    }
}
