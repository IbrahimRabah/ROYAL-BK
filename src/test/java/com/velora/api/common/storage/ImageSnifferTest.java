package com.velora.api.common.storage;

import static org.assertj.core.api.Assertions.assertThat;

import com.velora.api.testsupport.TestImages;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ImageSnifferTest {

    @Test
    @DisplayName("Recognises JPEG, PNG, WebP and AVIF by their first bytes")
    void recognisesTheFourFormats() {
        assertThat(ImageSniffer.detect(TestImages.jpeg())).contains("image/jpeg");
        assertThat(ImageSniffer.detect(TestImages.png())).contains("image/png");
        assertThat(ImageSniffer.detect(TestImages.webp())).contains("image/webp");
        assertThat(ImageSniffer.detect(TestImages.avif())).contains("image/avif");
    }

    @Test
    @DisplayName("HTML, text and an empty file are not images")
    void rejectsNonImages() {
        assertThat(ImageSniffer.detect(TestImages.html())).isEmpty();
        assertThat(ImageSniffer.detect("fake-image-bytes".getBytes(StandardCharsets.UTF_8))).isEmpty();
        assertThat(ImageSniffer.detect(new byte[0])).isEmpty();
        assertThat(ImageSniffer.detect(null)).isEmpty();
    }

    @Test
    @DisplayName("Formats the storefront does not accept are not recognised: GIF, SVG, HEIC, BMP")
    void rejectsOtherImageFormats() {
        assertThat(ImageSniffer.detect("GIF89a-and-some-more-bytes".getBytes(StandardCharsets.US_ASCII))).isEmpty();
        assertThat(ImageSniffer.detect("<svg xmlns=\"http://www.w3.org/2000/svg\"/>".getBytes(StandardCharsets.UTF_8))).isEmpty();
        assertThat(ImageSniffer.detect("BM......bitmap".getBytes(StandardCharsets.US_ASCII))).isEmpty();

        byte[] heic = TestImages.avif();
        System.arraycopy("heic".getBytes(StandardCharsets.US_ASCII), 0, heic, 8, 4);
        System.arraycopy("mif1".getBytes(StandardCharsets.US_ASCII), 0, heic, 16, 4);
        assertThat(ImageSniffer.detect(heic)).as("an ISO media file whose brands are not AVIF").isEmpty();
    }

    @Test
    @DisplayName("A header that is cut short does not match, and does not throw")
    void truncatedHeadersAreSafe() {
        assertThat(ImageSniffer.detect(new byte[] {(byte) 0xFF, (byte) 0xD8})).isEmpty();
        assertThat(ImageSniffer.detect(new byte[] {(byte) 0x89, 'P', 'N'})).isEmpty();
        assertThat(ImageSniffer.detect("RIFF".getBytes(StandardCharsets.US_ASCII))).isEmpty();
        assertThat(ImageSniffer.detect(new byte[] {0, 0, 0, 28, 'f', 't', 'y', 'p'})).isEmpty();
    }

    @Test
    @DisplayName("A RIFF container that is not WebP (e.g. WAV) is not an image")
    void riffThatIsNotWebp() {
        byte[] wav = new byte[16];
        System.arraycopy("RIFF".getBytes(StandardCharsets.US_ASCII), 0, wav, 0, 4);
        System.arraycopy("WAVE".getBytes(StandardCharsets.US_ASCII), 0, wav, 8, 4);
        assertThat(ImageSniffer.detect(wav)).isEmpty();
    }
}
