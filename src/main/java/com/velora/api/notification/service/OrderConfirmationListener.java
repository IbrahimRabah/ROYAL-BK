package com.velora.api.notification.service;

import com.velora.api.export.service.TemplateRenderer;
import com.velora.api.order.domain.CustomerOrder;
import com.velora.api.order.event.OrderPlacedEvent;
import com.velora.api.order.repository.OrderRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Sends the order confirmation email.
 *
 * <p>{@code AFTER_COMMIT} on purpose: {@code CheckoutService.placeOrder()} publishes
 * {@link OrderPlacedEvent} inside its own transaction, but this listener must only
 * run once that transaction has actually committed — an email for an order that then
 * rolled back would tell the customer something happened that did not.
 *
 * <p>This method opens its OWN transaction ({@code REQUIRES_NEW} — same reason
 * {@code AuditService.record()} does: by the time {@code AFTER_COMMIT} fires, the
 * transaction that published the event is already closed, so {@code REQUIRED}
 * propagation has nothing to join and Spring refuses it outright) purely to read
 * the order back with its items.
 */
@Component
public class OrderConfirmationListener {

    private static final Logger log = LoggerFactory.getLogger(OrderConfirmationListener.class);

    private final OrderRepository orderRepository;
    private final TemplateRenderer templateRenderer;
    private final MailService mailService;

    public OrderConfirmationListener(OrderRepository orderRepository,
                                     TemplateRenderer templateRenderer,
                                     MailService mailService) {
        this.orderRepository = orderRepository;
        this.templateRenderer = templateRenderer;
        this.mailService = mailService;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public void onOrderPlaced(OrderPlacedEvent event) {
        CustomerOrder order = orderRepository.findWithItemsById(event.orderId()).orElse(null);
        if (order == null) {
            // Should not happen — the order was just committed. Logged rather than
            // thrown: a notification module must never surface as a checkout error.
            log.warn("OrderPlacedEvent fired for order id={} but it could not be found",
                    event.orderId());
            return;
        }

        // Email is optional at registration and always optional for guest checkout —
        // silently do nothing rather than fail when there is nowhere to send to.
        if (order.getContactEmail() == null || order.getContactEmail().isBlank()) {
            return;
        }

        String html = templateRenderer.render("order-confirmation", "order", order);
        String subject = "طلبك رقم %s وصلنا".formatted(order.getOrderNumber());
        mailService.sendHtml(order.getContactEmail(), subject, html);
    }
}
