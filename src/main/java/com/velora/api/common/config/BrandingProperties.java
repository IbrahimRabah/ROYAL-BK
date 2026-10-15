package com.velora.api.common.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Bound from {@code velora.branding.*} in application.yml.
 *
 * <p>Deployment-level identity: the things a client should never have to touch. Anything
 * the client edits from the admin panel (legal name, address, phone) lives in the store
 * profile instead and takes precedence.
 *
 * <p>The number prefixes are stamped into numbers that are stored and printed forever.
 * Changing one affects only numbers issued afterwards.
 */
@Component
@ConfigurationProperties(prefix = "velora.branding")
public class BrandingProperties {

    /** Order numbers read {@code <prefix>-yyMMdd-NNNN}. */
    private String orderNumberPrefix = "RYL";

    /** Invoice numbers read {@code <prefix>-<year>-NNNNNN}. */
    private String invoiceNumberPrefix = "RYL-INV";

    /** Wordmark fallback for when the store profile has no legal name. */
    private String brandName = "ROYAL";

    public String getOrderNumberPrefix() {
        return orderNumberPrefix;
    }

    public void setOrderNumberPrefix(String orderNumberPrefix) {
        this.orderNumberPrefix = orderNumberPrefix;
    }

    public String getInvoiceNumberPrefix() {
        return invoiceNumberPrefix;
    }

    public void setInvoiceNumberPrefix(String invoiceNumberPrefix) {
        this.invoiceNumberPrefix = invoiceNumberPrefix;
    }

    public String getBrandName() {
        return brandName;
    }

    public void setBrandName(String brandName) {
        this.brandName = brandName;
    }
}
