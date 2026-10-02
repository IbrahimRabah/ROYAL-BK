package com.velora.api.customrequest.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * One counter row per year — the same design as {@code InvoiceSequence}, for the same
 * reason.
 *
 * <p>A database identity leaves a gap whenever a transaction rolls back. The customer is
 * given this number over the phone, so a gap is something someone notices. The number is
 * allocated under a row lock in the same transaction that writes the request, so a
 * rollback returns it instead of burning it.
 */
@Entity
@Table(name = "custom_order_request_sequence")
@Getter
@Setter
@NoArgsConstructor
public class CustomOrderRequestSequence {

    @Id
    @Column(name = "fiscal_year")
    private Integer fiscalYear;

    @Column(name = "last_number", nullable = false)
    private int lastNumber;

    public CustomOrderRequestSequence(Integer fiscalYear) {
        this.fiscalYear = fiscalYear;
        this.lastNumber = 0;
    }

    public int next() {
        return ++lastNumber;
    }
}
