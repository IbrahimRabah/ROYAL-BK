package com.velora.api.customrequest.domain;

import java.util.Set;

/**
 * Where a request is in its life. A request is not a sale at any point here: stock is
 * never reserved, and nothing is priced until it is {@link #QUOTED}.
 *
 * <pre>
 *   NEW ──► CONTACTED ──► QUOTED ──► ACCEPTED ──► CONVERTED   (CONVERTED: not yet reachable)
 *     └────────┴───────────┴──► REJECTED
 * </pre>
 *
 * <p>{@code QUOTED} is reached by giving a quote, from {@code NEW} or {@code CONTACTED}
 * (staff may quote without having logged a call). A quote is therefore always present
 * from {@code QUOTED} onwards, which is what makes {@code ACCEPTED} impossible without
 * one. {@code ACCEPTED}, {@code REJECTED} and {@code CONVERTED} cannot be left.
 */
public enum CustomRequestStatus {
    NEW,
    CONTACTED,
    QUOTED,
    ACCEPTED,
    REJECTED,
    CONVERTED;

    /** Statuses a staff member may set directly with the status endpoint. */
    public Set<CustomRequestStatus> directTransitions() {
        return switch (this) {
            case NEW -> Set.of(CONTACTED, REJECTED);
            case CONTACTED -> Set.of(REJECTED);
            case QUOTED -> Set.of(ACCEPTED, REJECTED);
            case ACCEPTED, REJECTED, CONVERTED -> Set.of();
        };
    }

    /** Statuses in which a quote may still be given or changed. */
    public boolean canBeQuoted() {
        return this == NEW || this == CONTACTED || this == QUOTED;
    }

    public boolean isTerminal() {
        return this == ACCEPTED || this == REJECTED || this == CONVERTED;
    }
}
