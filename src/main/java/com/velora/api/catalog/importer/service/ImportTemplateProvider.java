package com.velora.api.catalog.importer.service;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * The downloadable template: the furniture workbook itself, trimmed to its main and SEO sheets with
 * no data rows, plus the dropdown-list sheet its cells validate against. It is a classpath
 * resource, not generated, so what staff download is exactly what the upload accepts.
 * {@code ImportTemplateTest} fails the build if the resource and {@link ImportTemplate} drift apart.
 */
@Component
public class ImportTemplateProvider {

    private volatile byte[] cached;

    public byte[] template() {
        byte[] bytes = cached;
        if (bytes == null) {
            try (InputStream in = new ClassPathResource(ImportTemplate.RESOURCE).getInputStream()) {
                bytes = in.readAllBytes();
            } catch (IOException e) {
                throw new UncheckedIOException("The product import template is missing from the build", e);
            }
            cached = bytes;
        }
        return bytes.clone();
    }
}
