package pl.chrisitstyle.order.saga;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import pl.chrisitstyle.order.ProductClient;
import pl.chrisitstyle.order.ProductReservationResponse;
import pl.chrisitstyle.order.exception.OrderCreationException;

import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class OrderCreationSagaRecoveryService {

    private final ProductClient productClient;
    private final OrderCreationSagaStateService sagaStateService;

    public void recover(UUID sagaId) {
        OrderCreationSagaStatus status =
                sagaStateService.getStatus(sagaId);

        if (status.isTerminal()) {
            return;
        }

        log.warn(
                "Recovering interrupted order creation saga: sagaId={}, status={}",
                sagaId,
                status
        );

        if (status != OrderCreationSagaStatus.COMPENSATING) {
            sagaStateService.markCompensating(
                    sagaId,
                    "Recovered after interrupted order creation"
            );
        }

        List<OrderCreationSagaReservation> reservations =
                sagaStateService.getReservations(sagaId);

        for (int i = reservations.size() - 1;
             i >= 0;
             i--) {

            OrderCreationSagaReservation reservation =
                    reservations.get(i);

            boolean success =
                    compensateReservation(
                            sagaId,
                            reservation
                    );

            if (!success) {
                return;
            }
        }

        sagaStateService.markCompensated(sagaId);

        log.info(
                "Interrupted order creation saga compensated: sagaId={}",
                sagaId
        );
    }

    private boolean compensateReservation(
            UUID sagaId,
            OrderCreationSagaReservation reservation
    ) {
        return switch (reservation.getStatus()) {

            case RELEASED,
                 RESERVATION_FAILED -> true;

            case RESERVED ->
                    releaseReserved(
                            sagaId,
                            reservation
                    );

            case PLANNED ->
                    reconcilePlannedAndRelease(
                            sagaId,
                            reservation
                    );
        };
    }

    private boolean reconcilePlannedAndRelease(
            UUID sagaId,
            OrderCreationSagaReservation reservation
    ) {
        try {
            /*
             * We do not know whether the original HTTP call
             * succeeded before the order-service crashed.
             *
             * Repeating the same command with the same
             * idempotency key resolves that ambiguity.
             */
            ProductReservationResponse response =
                    productClient.reserve(
                            reservation.getProductId(),
                            reservation.getQuantity(),
                            reservation.getReservationKey()
                    );

            sagaStateService.markReserved(
                    reservation.getReservationKey(),
                    response.unitPrice()
            );

            return releaseReserved(
                    sagaId,
                    reservation
            );

        } catch (OrderCreationException definitiveRejection) {

            /*
             * The reservation was definitively rejected.
             * Therefore there is no successful stock side effect
             * that still requires compensation.
             */
            sagaStateService.markReservationFailed(
                    reservation.getReservationKey()
            );

            return true;

        } catch (RuntimeException unknownFailure) {

            markRecoveryFailed(
                    sagaId,
                    "Could not resolve unknown reservation outcome: "
                            + failureMessage(unknownFailure)
            );

            return false;
        }
    }

    private boolean releaseReserved(
            UUID sagaId,
            OrderCreationSagaReservation reservation
    ) {
        try {
            productClient.release(
                    reservation.getProductId(),
                    reservation.getQuantity(),
                    reservation.getReservationKey()
            );

            sagaStateService.markReleased(
                    reservation.getReservationKey()
            );

            return true;

        } catch (RuntimeException compensationFailure) {

            markRecoveryFailed(
                    sagaId,
                    "Could not release stock reservation: "
                            + failureMessage(compensationFailure)
            );

            return false;
        }
    }

    private void markRecoveryFailed(
            UUID sagaId,
            String reason
    ) {
        try {
            sagaStateService.markCompensationFailed(
                    sagaId,
                    reason
            );
        } catch (RuntimeException persistenceFailure) {
            log.error(
                    "Could not persist saga recovery failure: sagaId={}",
                    sagaId,
                    persistenceFailure
            );
        }
    }

    private String failureMessage(
            RuntimeException exception
    ) {
        if (exception.getMessage() == null
                || exception.getMessage().isBlank()) {

            return exception.getClass()
                    .getSimpleName();
        }

        return exception.getMessage();
    }
}
