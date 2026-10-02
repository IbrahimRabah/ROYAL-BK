package com.velora.api.common.exception;

import org.springframework.http.HttpStatus;

/**
 * Every expected failure in VELORA has a stable code here.
 *
 * <p>The Angular client branches on {@code code}, never on the message text.
 * The code is the contract; the message is presentation and gets translated in the
 * front end. That is why these strings stay in English — changing a wording should
 * never require redeploying the API, and the same code can be worded differently
 * on a checkout screen than in an admin table.
 */
public enum ErrorCode {

    // ---- generic ----
    VALIDATION_FAILED("Validation failed", HttpStatus.BAD_REQUEST),
    INVALID_REQUEST_BODY("The request body could not be read", HttpStatus.BAD_REQUEST),
    INVALID_PARAMETER("A parameter has the wrong type or format", HttpStatus.BAD_REQUEST),
    METHOD_NOT_ALLOWED("This HTTP method is not supported here", HttpStatus.METHOD_NOT_ALLOWED),
    RESOURCE_NOT_FOUND("Resource not found", HttpStatus.NOT_FOUND),
    UNAUTHORIZED("Authentication required", HttpStatus.UNAUTHORIZED),
    FORBIDDEN("You do not have permission to perform this action", HttpStatus.FORBIDDEN),
    INTERNAL_ERROR("An unexpected error occurred", HttpStatus.INTERNAL_SERVER_ERROR),

    /** Fallback for any database uniqueness constraint we did not check first. */
    DUPLICATE_VALUE("This value is already in use", HttpStatus.CONFLICT),
    REFERENCED_BY_OTHER_RECORDS("This record is used elsewhere and cannot be removed",
            HttpStatus.CONFLICT),
    /** A foreign key pointed at a parent row that does not exist. */
    INVALID_REFERENCE("This refers to a record that does not exist", HttpStatus.BAD_REQUEST),
    /** A NOT NULL column was left empty — the schema requires a field the request omitted. */
    REQUIRED_FIELD_MISSING("A required field is missing", HttpStatus.BAD_REQUEST),

    // ---- files ----
    FILE_TOO_LARGE("The file is larger than the allowed size", HttpStatus.PAYLOAD_TOO_LARGE),
    UNSUPPORTED_FILE_TYPE("This file type is not accepted", HttpStatus.BAD_REQUEST),
    FILE_REQUIRED("A file is required", HttpStatus.BAD_REQUEST),

    // ---- identity ----
    INVALID_CREDENTIALS("Invalid phone/email or password", HttpStatus.UNAUTHORIZED),
    ACCOUNT_SUSPENDED("This account has been suspended", HttpStatus.FORBIDDEN),
    EMAIL_ALREADY_EXISTS("This email is already registered", HttpStatus.CONFLICT),
    PHONE_ALREADY_EXISTS("This phone number is already registered", HttpStatus.CONFLICT),
    INVALID_PHONE_FORMAT("Not a valid Egyptian mobile number", HttpStatus.BAD_REQUEST),
    OTP_INVALID("The verification code is incorrect", HttpStatus.BAD_REQUEST),
    OTP_EXPIRED("The verification code has expired", HttpStatus.BAD_REQUEST),
    OTP_TOO_MANY_ATTEMPTS("Too many attempts. Request a new code", HttpStatus.TOO_MANY_REQUESTS),
    RATE_LIMITED("Too many requests. Please try again later", HttpStatus.TOO_MANY_REQUESTS),
    TOKEN_EXPIRED("Session expired. Please sign in again", HttpStatus.UNAUTHORIZED),
    TOKEN_INVALID("Invalid authentication token", HttpStatus.UNAUTHORIZED),

    // ---- catalog: products ----
    PRODUCT_NOT_FOUND("Product not found", HttpStatus.NOT_FOUND),
    VARIANT_NOT_FOUND("This option is not available", HttpStatus.NOT_FOUND),
    PRODUCT_NOT_ACTIVE("This product is not currently for sale", HttpStatus.CONFLICT),
    PRODUCT_NOT_PURCHASABLE("This product cannot be added to the cart",
            HttpStatus.CONFLICT),
    PRODUCT_HAS_NO_VARIANTS("Add at least one variant before publishing", HttpStatus.CONFLICT),
    PRODUCT_MISSING_ARABIC_NAME("An Arabic name is required before publishing",
            HttpStatus.CONFLICT),
    SKU_ALREADY_EXISTS("This SKU is already in use", HttpStatus.CONFLICT),
    BARCODE_ALREADY_EXISTS("This barcode is already in use", HttpStatus.CONFLICT),
    SLUG_ALREADY_EXISTS("This URL slug is already in use", HttpStatus.CONFLICT),
    VARIANT_COMBINATION_EXISTS("A variant with this combination already exists",
            HttpStatus.CONFLICT),

    // ---- catalog: taxonomy ----
    CATEGORY_NOT_FOUND("Category not found", HttpStatus.NOT_FOUND),
    CATEGORY_NOT_EMPTY("Move or archive the products in this category first",
            HttpStatus.CONFLICT),
    CATEGORY_CYCLE("A category cannot be its own parent", HttpStatus.BAD_REQUEST),
    BRAND_NOT_FOUND("Brand not found", HttpStatus.NOT_FOUND),
    BRAND_SLUG_EXISTS("A brand with this URL slug already exists", HttpStatus.CONFLICT),
    ATTRIBUTE_NOT_FOUND("Attribute not found", HttpStatus.NOT_FOUND),
    ATTRIBUTE_CODE_EXISTS("An attribute with this code already exists. Edit it instead of "
            + "creating a new one", HttpStatus.CONFLICT),
    ATTRIBUTE_VALUE_NOT_FOUND("Attribute value not found", HttpStatus.NOT_FOUND),
    ATTRIBUTE_VALUE_CODE_EXISTS("This value code already exists on the attribute",
            HttpStatus.CONFLICT),
    ATTRIBUTE_NOT_VARIANT_DEFINING("This attribute is informational and cannot generate "
            + "variants", HttpStatus.BAD_REQUEST),
    ATTRIBUTE_IN_USE("This attribute is used by existing variants and cannot be changed this "
            + "way", HttpStatus.CONFLICT),

    // ---- inventory ----
    STOCK_UNAVAILABLE("Not enough stock available", HttpStatus.CONFLICT),
    STOCK_BELOW_RESERVED("Stock cannot be reduced below the quantity reserved for orders in "
            + "progress", HttpStatus.CONFLICT),
    NEGATIVE_STOCK("Stock cannot go below zero", HttpStatus.BAD_REQUEST),
    INVENTORY_RECORD_MISSING("This variant has no inventory record", HttpStatus.CONFLICT),
    MOVEMENT_TYPE_NOT_MANUAL("This movement type is written by the order flow and cannot be "
            + "set manually", HttpStatus.BAD_REQUEST),
    RESERVATION_EXPIRED("Your checkout session expired. Please review your cart",
            HttpStatus.CONFLICT),
    CONCURRENT_STOCK_CHANGE("Stock changed while processing. Please try again",
            HttpStatus.CONFLICT),

    // ---- cart ----
    CART_EMPTY("Your cart is empty", HttpStatus.BAD_REQUEST),
    CART_ITEM_NOT_FOUND("This item is no longer in your cart", HttpStatus.NOT_FOUND),
    PRICE_CHANGED("Prices in your cart have changed. Please review", HttpStatus.CONFLICT),

    // ---- promotion ----
    COUPON_NOT_FOUND("Coupon code not recognised", HttpStatus.NOT_FOUND),
    COUPON_EXPIRED("This coupon has expired", HttpStatus.CONFLICT),
    COUPON_USAGE_LIMIT_REACHED("This coupon has reached its usage limit", HttpStatus.CONFLICT),
    COUPON_MINIMUM_NOT_MET("Your order does not reach the minimum for this coupon",
            HttpStatus.CONFLICT),
    COUPON_NOT_APPLICABLE("This coupon does not apply to the items in your cart",
            HttpStatus.CONFLICT),

    // ---- shipping ----
    GOVERNORATE_NOT_SERVED("We do not deliver to this governorate yet", HttpStatus.CONFLICT),
    SHIPPING_SIZE_MISSING("An item in the cart has no shipping size, so delivery cannot be "
            + "priced", HttpStatus.CONFLICT),
    SHIPPING_RATE_NOT_CONFIGURED("Shipping is not configured for this area",
            HttpStatus.CONFLICT),
    INVALID_ADDRESS("The delivery address is incomplete", HttpStatus.BAD_REQUEST),

    // ---- order ----
    ORDER_NOT_FOUND("Order not found", HttpStatus.NOT_FOUND),
    INVALID_STATUS_TRANSITION("This status change is not allowed", HttpStatus.CONFLICT),
    ORDER_CANNOT_BE_CANCELLED("This order can no longer be cancelled", HttpStatus.CONFLICT),
    RETURN_WINDOW_CLOSED("The return period for this order has ended", HttpStatus.CONFLICT),
    RETURN_QUANTITY_EXCEEDED("You cannot return more than you ordered", HttpStatus.BAD_REQUEST),
    DUPLICATE_ORDER("This order has already been submitted", HttpStatus.CONFLICT),

    // ---- payment ----
    PAYMENT_METHOD_UNAVAILABLE("This payment method is not available", HttpStatus.CONFLICT),
    REFUND_EXCEEDS_ORDER_TOTAL("Refund is greater than the order total",
            HttpStatus.BAD_REQUEST),

    // ---- review ----
    PURCHASE_REQUIRED("Only verified buyers can review this product",
            HttpStatus.UNPROCESSABLE_ENTITY),
    REVIEW_ALREADY_EXISTS("You have already reviewed this product", HttpStatus.CONFLICT);

    private final String defaultMessage;
    private final HttpStatus status;

    ErrorCode(String defaultMessage, HttpStatus status) {
        this.defaultMessage = defaultMessage;
        this.status = status;
    }

    public String getDefaultMessage() {
        return defaultMessage;
    }

    public HttpStatus getStatus() {
        return status;
    }
}
