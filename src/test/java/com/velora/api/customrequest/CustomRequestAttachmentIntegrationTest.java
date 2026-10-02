package com.velora.api.customrequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.velora.api.common.exception.BusinessException;
import com.velora.api.common.exception.ErrorCode;
import com.velora.api.customrequest.domain.CustomRequestStatus;
import com.velora.api.customrequest.dto.CustomRequestAttachmentResponse;
import com.velora.api.customrequest.dto.CustomRequestCreatedResponse;
import com.velora.api.customrequest.service.CustomRequestTokenService;
import com.velora.api.testsupport.TestImages;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

/**
 * Reference images on a request. The endpoint is public, request ids are sequential, so
 * the questions that matter are: who may attach to which request, how many, and what.
 */
class CustomRequestAttachmentIntegrationTest extends CustomRequestTestBase {

    @Autowired private CustomRequestTokenService tokenService;

    // ---------------------------------------------------------------- the happy path

    @Test
    @DisplayName("With the token from creation, an image is stored and attached")
    void validTokenAttachesAnImage() {
        CustomRequestCreatedResponse request = createAsGuest();

        CustomRequestAttachmentResponse attachment = upload(request, png("a.png"));

        assertThat(attachment.id()).isNotNull();
        assertThat(attachment.contentType()).isEqualTo("image/png");
        assertThat(attachment.url()).contains("custom-requests/").endsWith(".png");
        String key = jdbc.queryForObject("SELECT storage_key FROM custom_order_request_attachment "
                + "WHERE id = ?", String.class, attachment.id());
        assertThat(storageService.exists(key)).isTrue();
    }

    @Test
    @DisplayName("JPEG, PNG, WebP and AVIF are all accepted")
    void allFourFormatsAreAccepted() {
        CustomRequestCreatedResponse request = createAsGuest();

        assertThat(upload(request, file("a.jpg", "image/jpeg", TestImages.jpeg())).contentType())
                .isEqualTo("image/jpeg");
        assertThat(upload(request, file("b.png", "image/png", TestImages.png())).contentType())
                .isEqualTo("image/png");
        assertThat(upload(request, file("c.webp", "image/webp", TestImages.webp())).contentType())
                .isEqualTo("image/webp");
        assertThat(upload(request, file("d.avif", "image/avif", TestImages.avif())).contentType())
                .isEqualTo("image/avif");
    }

    @Test
    @DisplayName("Over HTTP: multipart upload with the X-Request-Token header")
    void uploadOverHttp() throws Exception {
        CustomRequestCreatedResponse request = createAsGuest();

        mvc.perform(multipart("/api/v1/custom-requests/{id}/attachments", request.id())
                        .file(png("photo.png"))
                        .header("X-Request-Token", request.attachmentToken())
                        .with(from(newIp())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.contentType").value("image/png"))
                .andExpect(jsonPath("$.url").isNotEmpty());
    }

    // ------------------------------------------------------------------ the limit

    @Test
    @DisplayName("Five images are fine; the sixth is refused and nothing is added")
    void sixthImageIsRefused() {
        CustomRequestCreatedResponse request = createAsGuest();
        for (int i = 1; i <= 5; i++) {
            upload(request, png("p" + i + ".png"));
        }

        assertThatThrownBy(() -> upload(request, png("sixth.png")))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
                    assertThat(e.getMessage()).contains("5");
                });
        assertThat(attachmentCount(request)).isEqualTo(5);
    }

    @Test
    @DisplayName("Racing uploads cannot exceed the limit: of eight at once, exactly five succeed")
    void concurrentUploadsRespectTheLimit() throws Exception {
        CustomRequestCreatedResponse request = createAsGuest();

        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Boolean>> results = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                String ip = newIp();
                MockMultipartFile image = png("race-" + i + ".png");
                results.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    try {
                        customRequestService.addAttachment(
                                request.id(), request.attachmentToken(), null, image, ip);
                        return true;
                    } catch (BusinessException ex) {
                        return false;
                    }
                }));
            }
            ready.await(10, TimeUnit.SECONDS);
            go.countDown();

            long succeeded = 0;
            for (Future<Boolean> f : results) {
                if (f.get(60, TimeUnit.SECONDS)) {
                    succeeded++;
                }
            }
            assertThat(succeeded).isEqualTo(5);
            assertThat(attachmentCount(request)).isEqualTo(5);
        } finally {
            pool.shutdownNow();
        }
    }

    // -------------------------------------------------------------------- the file

    @Test
    @DisplayName("A wrong file type is refused: text, PDF, and a declared type that is not allowed")
    void wrongFileTypeIsRefused() {
        CustomRequestCreatedResponse request = createAsGuest();

        assertRefused(request, file("notes.txt", "text/plain", "hello".getBytes(StandardCharsets.UTF_8)));
        assertRefused(request, file("doc.pdf", "application/pdf", "%PDF-1.7".getBytes(StandardCharsets.UTF_8)));
        assertRefused(request, file("anim.gif", "image/gif", "GIF89a......".getBytes(StandardCharsets.UTF_8)));
        assertThat(attachmentCount(request)).isZero();
    }

    @Test
    @DisplayName("HTML or a script sent as image/png is refused — the bytes are checked, not the label")
    void disguisedFileIsRefused() {
        CustomRequestCreatedResponse request = createAsGuest();

        assertRefused(request, file("evil.png", "image/png", TestImages.html()));
        assertRefused(request, file("evil.html", "image/jpeg", TestImages.html()));
        assertThat(attachmentCount(request)).isZero();
    }

    @Test
    @DisplayName("A real image uploaded under a dangerous filename is stored as an image, never as .html")
    void filenameDoesNotChooseTheStoredExtension() {
        CustomRequestCreatedResponse request = createAsGuest();

        CustomRequestAttachmentResponse attachment =
                upload(request, file("page.html", "image/png", TestImages.png()));

        assertThat(attachment.url()).endsWith(".png").doesNotContain("page");
    }

    @Test
    @DisplayName("A file over 5 MB is refused; an empty file is refused")
    void oversizedAndEmptyFilesAreRefused() {
        CustomRequestCreatedResponse request = createAsGuest();
        byte[] tooBig = TestImages.padded(TestImages.png(), 5 * 1024 * 1024 + 1);

        assertRefused(request, file("big.png", "image/png", tooBig));
        assertRefused(request, file("empty.png", "image/png", new byte[0]));
        assertThat(attachmentCount(request)).isZero();
    }

    @Test
    @DisplayName("Over HTTP, a call with no file part is a 400 FILE_REQUIRED, not a 500")
    void missingFilePart() throws Exception {
        CustomRequestCreatedResponse request = createAsGuest();

        mvc.perform(multipart("/api/v1/custom-requests/{id}/attachments", request.id())
                        .header("X-Request-Token", request.attachmentToken())
                        .with(from(newIp())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("FILE_REQUIRED"));
    }

    // ---------------------------------------------------------- who may attach

    @Test
    @DisplayName("No token: 404, exactly like a request that does not exist")
    void noTokenIsNotFound() {
        CustomRequestCreatedResponse request = createAsGuest();

        assertThatThrownBy(() -> customRequestService.addAttachment(
                request.id(), null, null, png("x.png"), newIp()))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(ErrorCode.CUSTOM_REQUEST_NOT_FOUND));
        assertThat(attachmentCount(request)).isZero();
    }

    @Test
    @DisplayName("Another request's token does not work — ids are sequential, tokens are not transferable")
    void tokenOfAnotherRequestIsNotFound() {
        CustomRequestCreatedResponse mine = createAsGuest();
        CustomRequestCreatedResponse someoneElses = createAsGuest();

        assertThatThrownBy(() -> customRequestService.addAttachment(
                someoneElses.id(), mine.attachmentToken(), null, png("x.png"), newIp()))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(ErrorCode.CUSTOM_REQUEST_NOT_FOUND));
        assertThat(attachmentCount(someoneElses)).isZero();
    }

    @Test
    @DisplayName("A tampered or expired token is refused")
    void tamperedAndExpiredTokensAreRefused() {
        CustomRequestCreatedResponse request = createAsGuest();

        String[] parts = request.attachmentToken().split("\\.");
        String extended = parts[0] + "." + (Long.parseLong(parts[1]) + 86_400) + "." + parts[2];
        String expired = tokenService.issue(request.id(), Instant.now().minus(Duration.ofHours(25))).value();

        for (String token : List.of(extended, expired, "garbage", "")) {
            assertThatThrownBy(() -> customRequestService.addAttachment(
                    request.id(), token, null, png("x.png"), newIp()))
                    .as("token '%s'", token)
                    .isInstanceOfSatisfying(BusinessException.class, e ->
                            assertThat(e.getErrorCode()).isEqualTo(ErrorCode.CUSTOM_REQUEST_NOT_FOUND));
        }
        assertThat(attachmentCount(request)).isZero();
    }

    @Test
    @DisplayName("A token issued 23 hours ago still works — it lasts a day")
    void tokenStillWorksAfterHours() {
        CustomRequestCreatedResponse request = createAsGuest();
        String token = tokenService.issue(request.id(), Instant.now().minus(Duration.ofHours(23))).value();

        assertThat(customRequestService.addAttachment(
                request.id(), token, null, png("late.png"), newIp()).id()).isNotNull();
    }

    @Test
    @DisplayName("'Not yours' and 'does not exist' are the same answer — message included")
    void notYoursLooksLikeNotFound() {
        CustomRequestCreatedResponse request = createAsGuest();

        BusinessException wrongToken = catchBusiness(() -> customRequestService.addAttachment(
                request.id(), "garbage", null, png("x.png"), newIp()));
        BusinessException unknownId = catchBusiness(() -> customRequestService.addAttachment(
                999_999_999L, "garbage", null, png("x.png"), newIp()));

        assertThat(wrongToken.getErrorCode()).isEqualTo(unknownId.getErrorCode());
        assertThat(wrongToken.getMessage()).isEqualTo(unknownId.getMessage());
    }

    @Test
    @DisplayName("The signed-in owner can attach without a token; a different signed-in user cannot")
    void ownerCanAttachWithoutAToken() throws Exception {
        Long owner = newUser("custom-req-owner-" + UUID.randomUUID().toString().substring(0, 8),
                "Own", "Er");
        Long stranger = newUser("custom-req-stranger-" + UUID.randomUUID().toString().substring(0, 8),
                "Stran", "Ger");
        CustomRequestCreatedResponse request = track(customRequestService.create(customWork(), owner, newIp()));

        mvc.perform(multipart("/api/v1/custom-requests/{id}/attachments", request.id())
                        .file(png("mine.png")).with(signedIn(owner, "CUSTOMER")).with(from(newIp())))
                .andExpect(status().isCreated());
        mvc.perform(multipart("/api/v1/custom-requests/{id}/attachments", request.id())
                        .file(png("theirs.png")).with(signedIn(stranger, "CUSTOMER")).with(from(newIp())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CUSTOM_REQUEST_NOT_FOUND"));
        assertThat(attachmentCount(request)).isEqualTo(1);
    }

    @Test
    @DisplayName("Once staff have picked a request up, it no longer takes images")
    void noImagesAfterTheRequestIsNoLongerNew() {
        CustomRequestCreatedResponse request = createAsGuest();
        upload(request, png("before.png"));
        adminService.changeStatus(request.id(), CustomRequestStatus.CONTACTED, null, staffId);

        assertThatThrownBy(() -> upload(request, png("after.png")))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(ErrorCode.CUSTOM_REQUEST_CLOSED));
        assertThat(attachmentCount(request)).isEqualTo(1);
    }

    // -------------------------------------------------------------- rate limit

    @Test
    @DisplayName("Thirty upload attempts an hour per IP; the thirty-first is a 429 even with a valid token")
    void uploadAttemptsAreRateLimited() {
        CustomRequestCreatedResponse request = createAsGuest();
        String ip = newIp();

        // Failed attempts count as well: that is what stops token guessing.
        for (int i = 0; i < 30; i++) {
            assertThatThrownBy(() -> customRequestService.addAttachment(
                    request.id(), "wrong", null, png("x.png"), ip))
                    .isInstanceOfSatisfying(BusinessException.class, e ->
                            assertThat(e.getErrorCode()).isEqualTo(ErrorCode.CUSTOM_REQUEST_NOT_FOUND));
        }

        assertThatThrownBy(() -> customRequestService.addAttachment(
                request.id(), request.attachmentToken(), null, png("x.png"), ip))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(ErrorCode.RATE_LIMITED));

        // Another address is untouched.
        assertThat(customRequestService.addAttachment(
                request.id(), request.attachmentToken(), null, png("ok.png"), newIp()).id()).isNotNull();
    }

    // ----------------------------------------------------------------- helpers

    private CustomRequestAttachmentResponse upload(CustomRequestCreatedResponse request,
                                                   MultipartFile file) {
        return customRequestService.addAttachment(
                request.id(), request.attachmentToken(), null, file, newIp());
    }

    private void assertRefused(CustomRequestCreatedResponse request, MultipartFile file) {
        assertThatThrownBy(() -> upload(request, file))
                .as("%s (%s)", file.getOriginalFilename(), file.getContentType())
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));
    }

    private int attachmentCount(CustomRequestCreatedResponse request) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM custom_order_request_attachment "
                + "WHERE request_id = ?", Integer.class, request.id());
    }

    private static MockMultipartFile png(String name) {
        return file(name, "image/png", TestImages.png());
    }

    private static MockMultipartFile file(String name, String contentType, byte[] bytes) {
        return new MockMultipartFile("file", name, contentType, bytes);
    }

    private static BusinessException catchBusiness(Runnable action) {
        try {
            action.run();
        } catch (BusinessException ex) {
            return ex;
        }
        throw new AssertionError("expected a BusinessException");
    }
}
