package com.velora.api.customrequest.web;

import com.velora.api.common.exception.BusinessException;
import com.velora.api.common.exception.ErrorCode;
import com.velora.api.common.ratelimit.ClientIpResolver;
import com.velora.api.customrequest.dto.CustomRequestAttachmentResponse;
import com.velora.api.customrequest.dto.CustomRequestCreateRequest;
import com.velora.api.customrequest.dto.CustomRequestCreatedResponse;
import com.velora.api.customrequest.service.CustomRequestService;
import com.velora.api.identity.security.UserPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@Tag(name = "Custom requests", description = "Ask for a size, a made-to-order piece or custom work")
@RestController
@RequestMapping("/api/v1/custom-requests")
public class CustomRequestController {

    private final CustomRequestService customRequestService;
    private final ClientIpResolver clientIpResolver;

    public CustomRequestController(CustomRequestService customRequestService,
                                   ClientIpResolver clientIpResolver) {
        this.customRequestService = customRequestService;
        this.clientIpResolver = clientIpResolver;
    }

    @Operation(summary = "Submit a custom request",
            description = """
                    No account needed. If the caller is signed in the request is linked to
                    their account. Limited to 10 per hour per IP address.

                    The response carries `attachmentToken`: it is the only proof the caller
                    created this request, and `POST /{id}/attachments` requires it. This is
                    not a sale — nothing is reserved and nothing is priced until staff quote.
                    """,
            security = {})
    @ApiResponse(responseCode = "201", description = "Received; tell the customer the requestNumber")
    @ApiResponse(responseCode = "429", description = "Too many requests from this address")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CustomRequestCreatedResponse create(
            @Valid @RequestBody CustomRequestCreateRequest body,
            @AuthenticationPrincipal UserPrincipal principal,
            HttpServletRequest request) {

        return customRequestService.create(
                body, principal == null ? null : principal.id(), clientIpResolver.resolve(request));
    }

    @Operation(summary = "Attach a reference image to a request",
            description = """
                    Up to 5 images per request, JPEG / PNG / WebP / AVIF, 5 MB each — the
                    same limits as product images, checked against the file's real bytes.
                    Only while the request is still NEW.

                    Send the token from the create response as `X-Request-Token`. A signed-in
                    customer may omit it for their own request. A missing, wrong, expired or
                    other-request token is a 404, identical to an unknown id. Limited to 30
                    per hour per IP address.
                    """,
            security = {})
    @ApiResponse(responseCode = "404", description = "No such request, or the token is not for it")
    @PostMapping(path = "/{id}/attachments", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public CustomRequestAttachmentResponse addAttachment(
            @PathVariable Long id,
            @RequestHeader(value = "X-Request-Token", required = false) String token,
            @RequestPart(value = "file", required = false) MultipartFile file,
            @AuthenticationPrincipal UserPrincipal principal,
            HttpServletRequest request) {

        if (file == null) {
            throw new BusinessException(ErrorCode.FILE_REQUIRED);
        }
        return customRequestService.addAttachment(
                id, token, principal == null ? null : principal.id(), file,
                clientIpResolver.resolve(request));
    }
}
