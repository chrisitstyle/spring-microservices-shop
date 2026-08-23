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
            long fence,
            long leaseMs
    ) {
        requireLease(
                sagaId,
                workerId,
                fence,
                leaseMs
        );

        OrderCreationSagaStatus status =
                sagaStateService.getStatus(sagaId);

        if (status.isTerminal()) {
            return;
        }

        log.warn(
                "Recovering interrupted order creation saga: "
                        + "sagaId={}, status={}, workerId={}, fence={}",
                sagaId,
                status,
                workerId,
                fence
        );

        if (status != OrderCreationSagaStatus.COMPENSATING) {
            requireLease(
                    sagaId,
                    workerId,
                    fence,
                    leaseMs
            );

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

            requireLease(
                    sagaId,
                    workerId,
                    fence,
                    leaseMs
            );

            OrderCreationSagaReservation reservation =
                    reservations.get(i);

            boolean success =
                    compensateReservation(
                            sagaId,
                            reservation,
                            workerId,
                            fence,
                            leaseMs
                    );

            if (!success) {
                return;
            }
        }

        requireLease(
                sagaId,
                workerId,
                fence,
                leaseMs
        );

        sagaStateService.markCompensated(sagaId);

        log.info(
                "Interrupted order creation saga compensated: "
                        + "sagaId={}, workerId={}, fence={}",
                sagaId,
                workerId,
                fence
        );
    }

    private boolean compensateReservation(
            UUID sagaId,
            OrderCreationSagaReservation reservation,
            String workerId,
            long fence,
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
                            fence,
                            leaseMs
                    );

            case PLANNED ->
                    reconcilePlannedAndRelease(
                            sagaId,
                            reservation,
                            workerId,
                            fence,
                            leaseMs
                    );
        };
    }

    private boolean reconcilePlannedAndRelease(
            UUID sagaId,
            OrderCreationSagaReservation reservation,
            String workerId,
            long fence,
            long leaseMs
    ) {
        try {
            /*
             * Check ownership before performing the remote side effect.
             */
            requireLease(
                    sagaId,
                    workerId,
                    fence,
                    leaseMs
            );

            ProductReservationResponse response =
                    productClient.reserve(
                            reservation.getProductId(),
                            reservation.getQuantity(),
                            reservation.getReservationKey()
                    );

            /*
             * The HTTP call may have taken long enough for the lease
             * to expire. Check ownership again before changing
             * local saga state.
             */
            requireLease(
                    sagaId,
                    workerId,
                    fence,
                    leaseMs
            );

            sagaStateService.markReserved(
                    reservation.getReservationKey(),
                    response.unitPrice()
            );

            return releaseReserved(
                    sagaId,
                    reservation,
                    workerId,
                    fence,
                    leaseMs
            );

        } catch (SagaRecoveryLeaseLostException leaseLost) {
            throw leaseLost;

        } catch (OrderCreationException definitiveRejection) {

            requireLease(
                    sagaId,
                    workerId,
                    fence,
                    leaseMs
            );

            sagaStateService.markReservationFailed(
                    reservation.getReservationKey()
            );

            return true;

        } catch (RuntimeException unknownFailure) {

            markRecoveryFailed(
                    sagaId,
                    workerId,
                    fence,
                    leaseMs,
                    "Could not resolve unknown reservation outcome: "
                            + failureMessage(unknownFailure)
            );

            return false;
        }
    }

    private boolean releaseReserved(
            UUID sagaId,
            OrderCreationSagaReservation reservation,
            String workerId,
            long fence,
            long leaseMs
    ) {
        try {
            /*
             * Make sure this worker still owns the saga before
             * executing the compensation.
             */
            requireLease(
                    sagaId,
                    workerId,
                    fence,
                    leaseMs
            );

            productClient.release(
                    reservation.getProductId(),
                    reservation.getQuantity(),
                    reservation.getReservationKey()
            );

            /*
             * The remote release may have succeeded while our lease
             * expired. Verify ownership again before persisting
             * RELEASED locally.
             *
             * If ownership was lost, the next worker can safely
             * repeat release because the operation is idempotent.
             */
            requireLease(
                    sagaId,
                    workerId,
                    fence,
                    leaseMs
            );

            sagaStateService.markReleased(
                    reservation.getReservationKey()
            );

            return true;

        } catch (SagaRecoveryLeaseLostException leaseLost) {
            throw leaseLost;

        } catch (RuntimeException compensationFailure) {

            markRecoveryFailed(
                    sagaId,
                    workerId,
                    fence,
                    leaseMs,
                    "Could not release stock reservation: "
                            + failureMessage(compensationFailure)
            );

            return false;
        }
    }

    private void requireLease(
            UUID sagaId,
            String workerId,
            long fence,
            long leaseMs
    ) {
        boolean renewed =
                claimService.renewLease(
                        sagaId,
                        workerId,
                        fence,
                        leaseMs
                );

        if (!renewed) {
            throw new SagaRecoveryLeaseLostException(
                    "Saga recovery lease lost: "
                            + "sagaId="
                            + sagaId
                            + ", workerId="
                            + workerId
                            + ", fence="
                            + fence
            );
        }
    }

    private void markRecoveryFailed(
            UUID sagaId,
            String workerId,
            long fence,
            long leaseMs,
            String reason
    ) {
        try {
            /*
             * A stale worker must not mark somebody else's saga
             * as COMPENSATION_FAILED.
             */
            requireLease(
                    sagaId,
                    workerId,
                    fence,
                    leaseMs
            );

            sagaStateService.markCompensationFailed(
                    sagaId,
                    reason
            );

        } catch (SagaRecoveryLeaseLostException leaseLost) {
            throw leaseLost;

        } catch (RuntimeException persistenceFailure) {

            log.error(
                    "Could not persist saga recovery failure: "
                            + "sagaId={}, workerId={}, fence={}",
                    sagaId,
                    workerId,
                    fence,
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

    private static final class SagaRecoveryLeaseLostException
            extends IllegalStateException {

        private SagaRecoveryLeaseLostException(
                String message
        ) {
            super(message);
        }
    }
}