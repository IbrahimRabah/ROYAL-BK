package com.velora.api.customrequest.repository;

import com.velora.api.common.util.PhoneNormalizer;
import com.velora.api.customrequest.domain.CustomOrderRequest;
import com.velora.api.customrequest.domain.CustomRequestStatus;
import com.velora.api.customrequest.domain.CustomRequestType;
import java.time.OffsetDateTime;
import java.util.Locale;
import org.springframework.data.jpa.domain.Specification;

/**
 * Composable filters for the admin list. Every inactive filter is an always-true
 * predicate rather than null — Spring Data JPA 4.x rejects a null in {@code and()}.
 */
public final class CustomRequestSpecifications {

    private CustomRequestSpecifications() {
        // utility class
    }

    private static Specification<CustomOrderRequest> alwaysTrue() {
        return (root, query, cb) -> cb.conjunction();
    }

    public static Specification<CustomOrderRequest> hasStatus(CustomRequestStatus status) {
        if (status == null) {
            return alwaysTrue();
        }
        return (root, query, cb) -> cb.equal(root.get("status"), status);
    }

    public static Specification<CustomOrderRequest> hasType(CustomRequestType type) {
        if (type == null) {
            return alwaysTrue();
        }
        return (root, query, cb) -> cb.equal(root.get("type"), type);
    }

    public static Specification<CustomOrderRequest> inGovernorate(Long governorateId) {
        if (governorateId == null) {
            return alwaysTrue();
        }
        return (root, query, cb) ->
                cb.equal(root.get("governorate").get("id"), governorateId);
    }

    public static Specification<CustomOrderRequest> createdFrom(OffsetDateTime from) {
        if (from == null) {
            return alwaysTrue();
        }
        return (root, query, cb) -> cb.greaterThanOrEqualTo(root.get("createdAt"), from);
    }

    public static Specification<CustomOrderRequest> createdBefore(OffsetDateTime to) {
        if (to == null) {
            return alwaysTrue();
        }
        return (root, query, cb) -> cb.lessThan(root.get("createdAt"), to);
    }

    /**
     * A phone number (in any local or international form) matches that number exactly;
     * anything else matches the contact name or the request number as a substring. The
     * phone is normalized with the same function that wrote it — otherwise
     * {@code 01012345678} would never find {@code +201012345678}.
     */
    public static Specification<CustomOrderRequest> matches(String raw) {
        if (raw == null || raw.isBlank()) {
            return alwaysTrue();
        }
        String text = raw.trim();
        String phone = PhoneNormalizer.toE164(text);
        String pattern = "%" + text.toLowerCase(Locale.ROOT)
                .replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";

        return (root, query, cb) -> {
            var byText = cb.or(
                    cb.like(cb.lower(root.get("contactName")), pattern, '\\'),
                    cb.like(cb.lower(root.get("requestNumber")), pattern, '\\'));
            return phone == null ? byText : cb.or(cb.equal(root.get("phone"), phone), byText);
        };
    }
}
