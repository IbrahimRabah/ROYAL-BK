package com.velora.api.order;

import static com.velora.api.order.domain.FulfillmentStatus.AWAITING_SCHEDULE;
import static com.velora.api.order.domain.FulfillmentStatus.CANCELLED;
import static com.velora.api.order.domain.FulfillmentStatus.CONFIRMED;
import static com.velora.api.order.domain.FulfillmentStatus.DELIVERED;
import static com.velora.api.order.domain.FulfillmentStatus.DELIVERY_FAILED;
import static com.velora.api.order.domain.FulfillmentStatus.OUT_FOR_DELIVERY;
import static com.velora.api.order.domain.FulfillmentStatus.PARTIALLY_RETURNED;
import static com.velora.api.order.domain.FulfillmentStatus.PENDING;
import static com.velora.api.order.domain.FulfillmentStatus.PROCESSING;
import static com.velora.api.order.domain.FulfillmentStatus.REFUSED_ON_DELIVERY;
import static com.velora.api.order.domain.FulfillmentStatus.RETURNED;
import static com.velora.api.order.domain.FulfillmentStatus.RETURNED_TO_SELLER;
import static com.velora.api.order.domain.FulfillmentStatus.SHIPPED;
import static org.assertj.core.api.Assertions.assertThat;

import com.velora.api.order.domain.FulfillmentStatus;
import com.velora.api.order.service.OrderStatusMachine;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * AWAITING_SCHEDULE sits between CONFIRMED and PROCESSING and is optional. Adding it must not
 * have changed any move that already existed.
 */
class OrderStatusMachineSchedulingTest {

    private OrderStatusMachine machine;

    @BeforeEach
    void setUp() {
        machine = new OrderStatusMachine();
    }

    @Test
    @DisplayName("From CONFIRMED: AWAITING_SCHEDULE, PROCESSING or CANCELLED — exactly these")
    void confirmedOptions() {
        assertThat(machine.allowedFrom(CONFIRMED))
                .containsExactlyInAnyOrder(AWAITING_SCHEDULE, PROCESSING, CANCELLED);
    }

    @Test
    @DisplayName("From AWAITING_SCHEDULE: PROCESSING or CANCELLED — exactly these")
    void awaitingScheduleOptions() {
        assertThat(machine.allowedFrom(AWAITING_SCHEDULE))
                .containsExactlyInAnyOrder(PROCESSING, CANCELLED);
    }

    @Test
    @DisplayName("It is optional: CONFIRMED still goes straight to PROCESSING")
    void theScheduleStepCanBeSkipped() {
        assertThat(machine.canTransition(CONFIRMED, PROCESSING)).isTrue();
        assertThat(machine.canTransition(CONFIRMED, AWAITING_SCHEDULE)).isTrue();
        assertThat(machine.canTransition(AWAITING_SCHEDULE, PROCESSING)).isTrue();
    }

    @Test
    @DisplayName("It cannot be entered from anywhere but CONFIRMED, and never left backwards or forwards past PROCESSING")
    void onlyOneDoorInAndOneWayOn() {
        for (FulfillmentStatus from : FulfillmentStatus.values()) {
            assertThat(machine.canTransition(from, AWAITING_SCHEDULE))
                    .as("%s -> AWAITING_SCHEDULE", from).isEqualTo(from == CONFIRMED);
        }
        for (FulfillmentStatus to : EnumSet.of(PENDING, CONFIRMED, SHIPPED, OUT_FOR_DELIVERY, DELIVERED,
                DELIVERY_FAILED, REFUSED_ON_DELIVERY, RETURNED_TO_SELLER, RETURNED, PARTIALLY_RETURNED)) {
            assertThat(machine.canTransition(AWAITING_SCHEDULE, to))
                    .as("AWAITING_SCHEDULE -> %s", to).isFalse();
        }
        assertThat(machine.canTransition(PROCESSING, AWAITING_SCHEDULE)).as("no going back").isFalse();
    }

    /** The table as it was before AWAITING_SCHEDULE existed. Every row must still be true. */
    private static final Map<FulfillmentStatus, Set<FulfillmentStatus>> BEFORE = Map.ofEntries(
            Map.entry(PENDING, EnumSet.of(CONFIRMED, CANCELLED)),
            Map.entry(CONFIRMED, EnumSet.of(PROCESSING, CANCELLED)),
            Map.entry(PROCESSING, EnumSet.of(SHIPPED, CANCELLED)),
            Map.entry(SHIPPED, EnumSet.of(OUT_FOR_DELIVERY, DELIVERY_FAILED, RETURNED_TO_SELLER)),
            Map.entry(OUT_FOR_DELIVERY, EnumSet.of(DELIVERED, DELIVERY_FAILED, REFUSED_ON_DELIVERY)),
            Map.entry(DELIVERY_FAILED, EnumSet.of(OUT_FOR_DELIVERY, REFUSED_ON_DELIVERY, RETURNED_TO_SELLER)),
            Map.entry(REFUSED_ON_DELIVERY, EnumSet.of(RETURNED_TO_SELLER)),
            Map.entry(DELIVERED, EnumSet.of(RETURNED, PARTIALLY_RETURNED)),
            Map.entry(PARTIALLY_RETURNED, EnumSet.of(RETURNED)),
            Map.entry(CANCELLED, EnumSet.noneOf(FulfillmentStatus.class)),
            Map.entry(RETURNED, EnumSet.noneOf(FulfillmentStatus.class)),
            Map.entry(RETURNED_TO_SELLER, EnumSet.noneOf(FulfillmentStatus.class)));

    @Test
    @DisplayName("Every move that existed before still works")
    void everyOldTransitionStillWorks() {
        BEFORE.forEach((from, targets) -> targets.forEach(to ->
                assertThat(machine.canTransition(from, to)).as("%s -> %s", from, to).isTrue()));
    }

    @Test
    @DisplayName("Nothing that was impossible before became possible, except the new moves")
    void nothingElseOpenedUp() {
        for (FulfillmentStatus from : FulfillmentStatus.values()) {
            if (from == AWAITING_SCHEDULE) {
                continue;
            }
            Set<FulfillmentStatus> now = EnumSet.noneOf(FulfillmentStatus.class);
            now.addAll(machine.allowedFrom(from));
            if (from == CONFIRMED) {
                now.remove(AWAITING_SCHEDULE);        // the one addition
            }
            assertThat(now).as("allowed from %s", from)
                    .containsExactlyInAnyOrderElementsOf(BEFORE.get(from));
        }
    }

    @Test
    @DisplayName("Cancellation is still possible only before dispatch — and AWAITING_SCHEDULE is before")
    void cancellationBoundaryIsUnchanged() {
        assertThat(machine.canTransition(AWAITING_SCHEDULE, CANCELLED)).isTrue();
        assertThat(machine.canTransition(SHIPPED, CANCELLED)).isFalse();
        assertThat(machine.canTransition(OUT_FOR_DELIVERY, CANCELLED)).isFalse();
        assertThat(AWAITING_SCHEDULE.isDispatched()).as("nothing has left the warehouse").isFalse();
        assertThat(AWAITING_SCHEDULE.isTerminal()).isFalse();
    }

    @Test
    @DisplayName("A delivery date can be set from confirmation until delivery, including after a failed attempt")
    void whenAnAppointmentCanBeSet() {
        assertThat(EnumSet.allOf(FulfillmentStatus.class).stream()
                .filter(FulfillmentStatus::canBeScheduled).toList())
                .containsExactlyInAnyOrder(CONFIRMED, AWAITING_SCHEDULE, PROCESSING, SHIPPED,
                        OUT_FOR_DELIVERY, DELIVERY_FAILED);
        assertThat(PENDING.canBeScheduled()).as("confirm the order first").isFalse();
        assertThat(DELIVERED.canBeScheduled()).isFalse();
        assertThat(CANCELLED.canBeScheduled()).isFalse();
    }
}
