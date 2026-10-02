package com.velora.api.customrequest.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** One reference image a customer attached to a request. */
@Entity
@Table(name = "custom_order_request_attachment")
@Getter
@Setter
@NoArgsConstructor
public class CustomRequestAttachment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "request_id", nullable = false)
    private CustomOrderRequest request;

    /** Storage key, never a URL — see {@code StorageService}. */
    @Column(name = "storage_key", nullable = false, length = 500)
    private String storageKey;

    @Column(name = "content_type", nullable = false, length = 50)
    private String contentType;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now(ZoneOffset.UTC);
}
