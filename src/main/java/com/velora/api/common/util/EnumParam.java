package com.velora.api.common.util;

import com.velora.api.common.exception.BusinessException;
import com.velora.api.common.exception.ErrorCode;
import java.util.Arrays;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * Parses a query-param string into an enum, or fails with a clean 400 naming the
 * valid values.
 *
 * <p>{@code Enum.valueOf()} throws a bare {@code IllegalArgumentException} whose
 * message names the fully-qualified enum class — fine for a stack trace, not for an
 * API response. Left unguarded at a controller or Specification boundary, that
 * exception also isn't a {@link BusinessException}, so it skips every specific
 * {@code GlobalExceptionHandler} mapping and surfaces as a bare 500. This happened
 * three times over three different enums (payment status, audit action, and again
 * here) before being consolidated into one call site.
 */
public final class EnumParam {

    private EnumParam() {
        // utility class
    }

    public static <E extends Enum<E>> E parse(Class<E> type, String raw, String paramName) {
        try {
            return Enum.valueOf(type, raw.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            String validValues = Arrays.stream(type.getEnumConstants())
                    .map(Enum::name)
                    .collect(Collectors.joining(", "));
            throw new BusinessException(ErrorCode.INVALID_PARAMETER,
                    "'%s' is not a valid %s. Valid values: %s"
                            .formatted(raw, paramName, validValues));
        }
    }
}
