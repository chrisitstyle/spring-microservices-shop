package pl.chrisitstyle.order.saga;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

class OrderCreationSagaTest {

    @Test
    void shouldCompleteSagaUsingHappyPath() {
        OrderCreationSaga saga = OrderCreationSaga.start(1L);

        saga.markReservingStock();
        saga.markStockReserved();
        saga.markCompletingOrder();
        saga.markCompleted(100L);

        assertEquals(
                OrderCreationSagaStatus.COMPLETED,
                saga.getStatus()
        );

        assertEquals(100L, saga.getOrderId());
        assertTrue(saga.getStatus().isTerminal());
    }

    @Test
    void shouldCompensateFailedSaga() {
        OrderCreationSaga saga = OrderCreationSaga.start(1L);

        saga.markReservingStock();
        saga.markCompensating("Stock reservation failed");
        saga.markCompensated();

        assertEquals(
                OrderCreationSagaStatus.COMPENSATED,
                saga.getStatus()
        );

        assertTrue(saga.getStatus().isTerminal());
    }

    @Test
    void shouldRejectInvalidTransition() {
        OrderCreationSaga saga = OrderCreationSaga.start(1L);

        assertThrows(
                IllegalStateException.class,
                saga::markStockReserved
        );
    }

    @Test
    void shouldTrackReservationLifecycle() {
        OrderCreationSaga saga = OrderCreationSaga.start(1L);

        OrderCreationSagaReservation reservation =
                OrderCreationSagaReservation.plan(
                        saga.getId(),
                        10L,
                        2,
                        0
                );

        assertEquals(
                SagaReservationStatus.PLANNED,
                reservation.getStatus()
        );

        assertNotNull(reservation.getReservationKey());

        reservation.markReserved(
                new BigDecimal("19.99")
        );

        assertEquals(
                SagaReservationStatus.RESERVED,
                reservation.getStatus()
        );

        reservation.markReleased();

        assertEquals(
                SagaReservationStatus.RELEASED,
                reservation.getStatus()
        );
    }
}
