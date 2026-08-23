package pl.chrisitstyle.order.saga;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import pl.chrisitstyle.order.ProductClient;
import pl.chrisitstyle.order.ProductReservationResponse;
import pl.chrisitstyle.order.exception.OrderCreationException;
import pl.chrisitstyle.order.exception.SagaRecoveryFencingException;

import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class OrderCreationSagaRecoveryService {

    private final ProductClient productClient;

    private final OrderCreationSagaStateService sagaStateService;

    private final OrderCreationSagaFencedStateService
            fencedStateService;

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
                sagaStateService.getStatus(
                        sagaId
                );

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

        if (status
                != OrderCreationSagaStatus.COMPENSATING) {

            fencedStateService.markCompensating(
                    sagaId,
                    workerId,
                    fence,
                    leaseMs,
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

        fencedStateService.markCompensated(
                sagaId,
                workerId,
                fence
        );

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
             * Renew and verify the lease before the remote side effect.
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
             * This update is fenced atomically in PostgreSQL.
             */
            fencedStateService.markReserved(
                    sagaId,
                    reservation.getReservationKey(),
                    response.unitPrice(),
                    workerId,
                    fence,
                    leaseMs
            );

            return releaseReserved(
                    sagaId,
                    reservation,
                    workerId,
                    fence,
                    leaseMs
            );

        } catch (SagaRecoveryFencingException fencingFailure) {

            throw fencingFailure;

        } catch (OrderCreationException definitiveRejection) {

            fencedStateService.markReservationFailed(
                    sagaId,
                    reservation.getReservationKey(),
                    workerId,
                    fence,
                    leaseMs
            );

            return true;

        } catch (RuntimeException unknownFailure) {

            markRecoveryFailed(
                    sagaId,
                    workerId,
                    fence,
                    leaseMs,
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
            long fence,
            long leaseMs
    ) {
        try {
            /*
             * Verify ownership immediately before the remote
             * compensation.
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
             * The local RELEASED transition verifies owner,
             * fence and lease in the same PostgreSQL statement.
             */
            fencedStateService.markReleased(
                    sagaId,
                    reservation.getReservationKey(),
                    workerId,
                    fence,
                    leaseMs
            );

            return true;

        } catch (SagaRecoveryFencingException fencingFailure) {

            throw fencingFailure;

        } catch (RuntimeException compensationFailure) {

            markRecoveryFailed(
                    sagaId,
                    workerId,
                    fence,
                    leaseMs,
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
            throw new SagaRecoveryFencingException(
                    "Saga recovery ownership lost: "
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
            fencedStateService.markCompensationFailed(
                    sagaId,
                    workerId,
                    fence,
                    leaseMs,
                    reason
            );

        } catch (SagaRecoveryFencingException fencingFailure) {

            throw fencingFailure;

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
}