package pl.chrisitstyle.order.saga;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class OrderCreationSagaStateService {

    private static final int MAX_FAILURE_REASON_LENGTH = 1000;

    private final OrderCreationSagaRepository sagaRepository;
    private final OrderCreationSagaReservationRepository reservationRepository;

    @Transactional
    public OrderCreationSaga start(Long userId) {
        OrderCreationSaga saga =
                OrderCreationSaga.start(userId);

        return sagaRepository.save(saga);
    }

    @Transactional
    public void markReservingStock(UUID sagaId) {
        OrderCreationSaga saga = getSaga(sagaId);
        saga.markReservingStock();
    }

    @Transactional
    public OrderCreationSagaReservation planReservation(
            UUID sagaId,
            Long productId,
            Integer quantity,
            Integer itemIndex
    ) {
        OrderCreationSaga saga = getSaga(sagaId);

        if (saga.getStatus()
                != OrderCreationSagaStatus.RESERVING_STOCK) {

            throw new IllegalStateException(
                    "Saga "
                            + sagaId
                            + " is not reserving stock"
            );
        }

        OrderCreationSagaReservation reservation =
                OrderCreationSagaReservation.plan(
                        sagaId,
                        productId,
                        quantity,
                        itemIndex
                );

        saga.recordProgress();
        return reservationRepository.save(reservation);
    }

    @Transactional
    public void markReserved(
            UUID reservationKey,
            BigDecimal unitPrice
    ) {
        OrderCreationSagaReservation reservation =
                getReservation(reservationKey);

        reservation.markReserved(unitPrice);

        getSaga(reservation.getSagaId())
                .recordProgress();
    }

    @Transactional
    public void markReservationFailed(
            UUID reservationKey
    ) {
        OrderCreationSagaReservation reservation =
                getReservation(reservationKey);

        reservation.markReservationFailed();

        getSaga(reservation.getSagaId())
                .recordProgress();
    }

    @Transactional
    public void markStockReserved(UUID sagaId) {
        OrderCreationSaga saga = getSaga(sagaId);
        saga.markStockReserved();
    }

    @Transactional
    public void markCompensating(
            UUID sagaId,
            String reason
    ) {
        OrderCreationSaga saga = getSaga(sagaId);

        saga.markCompensating(
                normalizeFailureReason(reason)
        );
    }

    @Transactional
    public void markReleased(UUID reservationKey) {
        OrderCreationSagaReservation reservation =
                getReservation(reservationKey);

        reservation.markReleased();

        getSaga(reservation.getSagaId())
                .recordProgress();
    }

    @Transactional
    public void markCompensated(UUID sagaId) {
        OrderCreationSaga saga = getSaga(sagaId);
        saga.markCompensated();
    }

    @Transactional
    public void markCompensationFailed(
            UUID sagaId,
            String reason
    ) {
        OrderCreationSaga saga = getSaga(sagaId);

        saga.markCompensationFailed(
                normalizeFailureReason(reason)
        );
    }

    @Transactional(readOnly = true)
    public List<OrderCreationSagaReservation> getReservations(
            UUID sagaId
    ) {
        return reservationRepository
                .findAllBySagaIdOrderByItemIndexAsc(sagaId);
    }

    private OrderCreationSaga getSaga(UUID sagaId) {
        return sagaRepository.findById(sagaId)
                .orElseThrow(() ->
                        new IllegalStateException(
                                "Order creation saga "
                                        + sagaId
                                        + " not found"
                        )
                );
    }

    @Transactional(readOnly = true)
    public OrderCreationSagaStatus getStatus(UUID sagaId) {
        return getSaga(sagaId).getStatus();
    }

    @Transactional(readOnly = true)
    public List<UUID> findRecoverableSagaIds(
            Instant staleBefore
    ) {
        return sagaRepository
                .findAllByStatusInAndUpdatedAtBefore(
                        List.of(
                                OrderCreationSagaStatus.STARTED,
                                OrderCreationSagaStatus.RESERVING_STOCK,
                                OrderCreationSagaStatus.STOCK_RESERVED,
                                OrderCreationSagaStatus.COMPLETING_ORDER,
                                OrderCreationSagaStatus.COMPENSATING,
                                OrderCreationSagaStatus.COMPENSATION_FAILED
                        ),
                        staleBefore
                )
                .stream()
                .map(OrderCreationSaga::getId)
                .toList();
    }

    private OrderCreationSagaReservation getReservation(
            UUID reservationKey
    ) {
        return reservationRepository
                .findByReservationKey(reservationKey)
                .orElseThrow(() ->
                        new IllegalStateException(
                                "Saga reservation "
                                        + reservationKey
                                        + " not found"
                        )
                );
    }

    private String normalizeFailureReason(String reason) {
        if (reason == null || reason.isBlank()) {
            return "Unknown saga failure";
        }

        if (reason.length() <= MAX_FAILURE_REASON_LENGTH) {
            return reason;
        }

        return reason.substring(
                0,
                MAX_FAILURE_REASON_LENGTH
        );
    }
}
