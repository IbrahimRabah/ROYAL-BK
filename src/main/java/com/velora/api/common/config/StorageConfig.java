package com.velora.api.common.config;

import com.velora.api.common.storage.StorageProperties;
import java.io.IOException;
import java.nio.file.Paths;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.lang.NonNull;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.PathResourceResolver;

/**
 * Serves uploaded images over HTTP from the storage folder.
 *
 * <p>This exists only because V1 stores files on local disk. Once storage moves to
 * R2 or S3 the CDN serves them and this class is deleted — nothing else changes.
 */
@Configuration
@EnableConfigurationProperties(StorageProperties.class)
public class StorageConfig implements WebMvcConfigurer {

    private final StorageProperties properties;

    public StorageConfig(StorageProperties properties) {
        this.properties = properties;
    }

    @Override
    public void addResourceHandlers(@NonNull ResourceHandlerRegistry registry) {
        String location = Paths.get(properties.getLocalPath())
                .toAbsolutePath().normalize().toUri().toString();

        registry.addResourceHandler("/uploads/**")
                .addResourceLocations(location)
                .setCachePeriod(60 * 60 * 24 * 30)   // 30 days; keys are immutable
                .resourceChain(true)
                .addResolver(new PathResourceResolver() {
                    @Override
                    protected Resource getResource(@NonNull String resourcePath,
                                                   @NonNull Resource location) throws IOException {
                        // Invoice PDFs are private. SecurityConfig already denies the URL; this
                        // keeps them unreachable even if that rule is ever loosened or bypassed.
                        if (resourcePath.startsWith("invoices/") || resourcePath.startsWith("/invoices/")) {
                            return null;
                        }
                        return super.getResource(resourcePath, location);
                    }
                });
    }
}
