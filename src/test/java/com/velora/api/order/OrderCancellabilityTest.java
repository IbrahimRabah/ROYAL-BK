package com.velora.api.order;

import static org.assertj.core.api.Assertions.assertThat;

import com.velora.api.order.domain.CustomerOrder;
import com.velora.api.order.domain.FulfillmentStatus;
import com.velora.api.order.service.OrderStatusMachine;
import java.util.EnumSet;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The {@code cancellable} flag the client sees and the cancel operation must be one rule. Both
 * read the transition table, so this walks every status and checks they agree.
 */
class OrderCancellabilityTest {

    private static final Set<FulfillmentStatus> CANCELLABLE = EnumSet.of(
            FulfillmentStatus.PENDING, FulfillmentStatus.CONFIRMED,
            FulfillmentStatus.AWAITING_SCHEDULE, FulfillmentStatus.PROCESSING);

    private final OrderStatusMachine machine = new OrderStatusMachine();

    @Test
    @DisplayName("For every status, the flag equals what the cancel operation accepts")
    void flagAgreesWithOperationForEveryStatus() {
        for (FulfillmentStatus status : FulfillmentStatus.values()) {
            CustomerOrder order = new CustomerOrder();
            order.setFulfillmentStatus(status);

            boolean operationAccepts = machine.canTransition(status, FulfillmentStatus.CANCELLED);

            assertThat(order.isCancellable())
                    .as("cancellable flag for %s", status)
                    .isEqualTo(operationAccepts);
            assertThat(order.isCancellable())
                    .as("expected answer for %s", status)
                    .isEqualTo(CANCELLABLE.contains(status));
        }
    }

    @Test
    @DisplayName("Returned and already-cancelled orders are not cancellable")
    void returnedStatusesAreNot() {
        for (FulfillmentStatus status : EnumSet.of(FulfillmentStatus.RETURNED,
                FulfillmentStatus.RETURNED_TO_SELLER, FulfillmentStatus.PARTIALLY_RETURNED,
                FulfillmentStatus.CANCELLED)) {
            CustomerOrder order = new CustomerOrder();
            order.setFulfillmentStatus(status);
            assertThat(order.isCancellable()).as(status.name()).isFalse();
        }
    }
}
