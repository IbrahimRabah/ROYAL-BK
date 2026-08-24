package com.velora.api.order.event;

/**
 * Published after an order commits — never inside {@code CheckoutService}'s
 * transaction, or a listener side effect (an email, eventually an SMS) would fire
 * for an order that then rolls back. Listeners must be
 * {@code @TransactionalEventListener(phase = AFTER_COMMIT)}, not {@code @EventListener}.
 */
public record OrderPlacedEvent(Long orderId) {
}
