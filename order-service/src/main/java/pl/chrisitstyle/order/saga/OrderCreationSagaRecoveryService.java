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
    private final OrderCreationSagaClaimService claimService;

    public void recover(
            UUID sagaId,
            String workerId,
            long leaseMs
    ) {
        requireLease(
                sagaId,
                workerId,
                leaseMs
        );

        OrderCreationSagaStatus status =
                sagaStateService.getStatus(
                        sagaId
                );

        if (status.isTerminal()) {
            return;
        }

        log.warn(
                "Recovering interrupted order creation saga: "
                        + "sagaId={}, status={}, workerId={}",
                sagaId,
                status,
                workerId
        );

        if (status
                != OrderCreationSagaStatus.COMPENSATING) {

            sagaStateService.markCompensating(
                    sagaId,
                    "Recovered after interrupted order creation"
            );
        }

        List<OrderCreationSagaReservation> reservations =
                sagaStateService.getReservations(
                        sagaId
                );

        for (int i = reservations.size() - 1;
             i >= 0;
             i--) {

            requireLease(
                    sagaId,
                    workerId,
                    leaseMs
            );

            OrderCreationSagaReservation reservation =
                    reservations.get(i);

            boolean success =
                    compensateReservation(
                            sagaId,
                            reservation,
                            workerId,
                            leaseMs
                    );

            if (!success) {
                return;
            }
        }

        requireLease(
                sagaId,
                workerId,
                leaseMs
        );

        sagaStateService.markCompensated(
                sagaId
        );

        log.info(
                "Interrupted order creation saga compensated: "
                        + "sagaId={}, workerId={}",
                sagaId,
                workerId
        );
    }

    private boolean compensateReservation(
            UUID sagaId,
            OrderCreationSagaReservation reservation,
            String workerId,
            long leaseMs
    ) {
        return switch (reservation.getStatus()) {

            case RELEASED,
                 RESERVATION_FAILED -> true;

            case RESERVED ->
                    releaseReserved(
                            sagaId,
                            reservation,
                            workerId,
                            leaseMs
                    );

            case PLANNED ->
                    reconcilePlannedAndRelease(
                            sagaId,
                            reservation,
                            workerId,
                            leaseMs
                    );
        };
    }

    private boolean reconcilePlannedAndRelease(
            UUID sagaId,
            OrderCreationSagaReservation reservation,
            String workerId,
            long leaseMs
    ) {
        try {
            requireLease(
                    sagaId,
                    workerId,
                    leaseMs
            );

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
                    reservation,
                    workerId,
                    leaseMs
            );

        } catch (OrderCreationException definitiveRejection) {

            sagaStateService.markReservationFailed(
                    reservation.getReservationKey()
            );

            return true;

        } catch (RuntimeException unknownFailure) {

            markRecoveryFailed(
                    sagaId,
                    "Could not resolve unknown reservation outcome: "
                            + failureMessage(
                            unknownFailure
                    )
            );

            return false;
        }
    }

    private boolean releaseReserved(
            UUID sagaId,
            OrderCreationSagaReservation reservation,
            String workerId,
            long leaseMs
    ) {
        try {
            requireLease(
                    sagaId,
                    workerId,
                    leaseMs
            );

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
                            + failureMessage(
                            compensationFailure
                    )
            );

            return false;
        }
    }

    private void requireLease(
            UUID sagaId,
            String workerId,
            long leaseMs
    ) {
        boolean renewed =
                claimService.renewLease(
                        sagaId,
                        workerId,
                        leaseMs
                );

        if (!renewed) {
            throw new IllegalStateException(
                    "Saga recovery lease lost: sagaId="
                            + sagaId
                            + ", workerId="
                            + workerId
            );
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