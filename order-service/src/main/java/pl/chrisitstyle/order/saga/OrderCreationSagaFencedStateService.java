package pl.chrisitstyle.order.saga;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pl.chrisitstyle.order.exception.SagaRecoveryFencingException;

import java.math.BigDecimal;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class OrderCreationSagaFencedStateService {

    private static final int MAX_FAILURE_REASON_LENGTH = 1000;

    private final NamedParameterJdbcTemplate jdbcTemplate;

    @Transactional
    public void markCompensating(
            UUID sagaId,
            String workerId,
            long fence,
            long leaseMs,
            String reason
    ) {
        String sql = """
                UPDATE order_creation_sagas
                SET status = 'COMPENSATING',
                    failure_reason = :reason,
                    updated_at = CURRENT_TIMESTAMP,
                    recovery_lease_until =
                        CURRENT_TIMESTAMP
                        + (:leaseMs * INTERVAL '1 millisecond')
                WHERE id = :sagaId
                  AND recovery_owner = :workerId
                  AND recovery_fence = :fence
                  AND recovery_lease_until >= CURRENT_TIMESTAMP
                  AND status IN (
                      'STARTED',
                      'RESERVING_STOCK',
                      'STOCK_RESERVED',
                      'COMPLETING_ORDER',
                      'COMPENSATION_FAILED'
                  )
                """;

        MapSqlParameterSource parameters =
                baseParameters(
                        sagaId,
                        workerId,
                        fence,
                        leaseMs
                )
                        .addValue(
                                "reason",
                                normalizeFailureReason(reason)
                        );

        requireSingleUpdate(
                jdbcTemplate.update(sql, parameters),
                sagaId,
                workerId,
                fence,
                "mark COMPENSATING"
        );
    }

    @Transactional
    public void markReserved(
            UUID sagaId,
            UUID reservationKey,
            BigDecimal unitPrice,
            String workerId,
            long fence,
            long leaseMs
    ) {
        String sql = """
                WITH owned_saga AS (
                    UPDATE order_creation_sagas
                    SET updated_at = CURRENT_TIMESTAMP,
                        recovery_lease_until =
                            CURRENT_TIMESTAMP
                            + (:leaseMs * INTERVAL '1 millisecond')
                    WHERE id = :sagaId
                      AND recovery_owner = :workerId
                      AND recovery_fence = :fence
                      AND recovery_lease_until >= CURRENT_TIMESTAMP
                      AND status = 'COMPENSATING'
                    RETURNING id
                )
                UPDATE order_creation_saga_reservations reservation
                SET status = 'RESERVED',
                    unit_price = :unitPrice,
                    updated_at = CURRENT_TIMESTAMP
                FROM owned_saga
                WHERE reservation.saga_id = owned_saga.id
                  AND reservation.reservation_key = :reservationKey
                  AND reservation.status = 'PLANNED'
                """;

        MapSqlParameterSource parameters =
                baseParameters(
                        sagaId,
                        workerId,
                        fence,
                        leaseMs
                )
                        .addValue(
                                "reservationKey",
                                reservationKey
                        )
                        .addValue(
                                "unitPrice",
                                unitPrice
                        );

        requireSingleUpdate(
                jdbcTemplate.update(sql, parameters),
                sagaId,
                workerId,
                fence,
                "mark reservation RESERVED"
        );
    }

    @Transactional
    public void markReservationFailed(
            UUID sagaId,
            UUID reservationKey,
            String workerId,
            long fence,
            long leaseMs
    ) {
        String sql = """
                WITH owned_saga AS (
                    UPDATE order_creation_sagas
                    SET updated_at = CURRENT_TIMESTAMP,
                        recovery_lease_until =
                            CURRENT_TIMESTAMP
                            + (:leaseMs * INTERVAL '1 millisecond')
                    WHERE id = :sagaId
                      AND recovery_owner = :workerId
                      AND recovery_fence = :fence
                      AND recovery_lease_until >= CURRENT_TIMESTAMP
                      AND status = 'COMPENSATING'
                    RETURNING id
                )
                UPDATE order_creation_saga_reservations reservation
                SET status = 'RESERVATION_FAILED',
                    updated_at = CURRENT_TIMESTAMP
                FROM owned_saga
                WHERE reservation.saga_id = owned_saga.id
                  AND reservation.reservation_key = :reservationKey
                  AND reservation.status = 'PLANNED'
                """;

        MapSqlParameterSource parameters =
                baseParameters(
                        sagaId,
                        workerId,
                        fence,
                        leaseMs
                )
                        .addValue(
                                "reservationKey",
                                reservationKey
                        );

        requireSingleUpdate(
                jdbcTemplate.update(sql, parameters),
                sagaId,
                workerId,
                fence,
                "mark reservation RESERVATION_FAILED"
        );
    }

    @Transactional
    public void markReleased(
            UUID sagaId,
            UUID reservationKey,
            String workerId,
            long fence,
            long leaseMs
    ) {
        String sql = """
                WITH owned_saga AS (
                    UPDATE order_creation_sagas
                    SET updated_at = CURRENT_TIMESTAMP,
                        recovery_lease_until =
                            CURRENT_TIMESTAMP
                            + (:leaseMs * INTERVAL '1 millisecond')
                    WHERE id = :sagaId
                      AND recovery_owner = :workerId
                      AND recovery_fence = :fence
                      AND recovery_lease_until >= CURRENT_TIMESTAMP
                      AND status = 'COMPENSATING'
                    RETURNING id
                )
                UPDATE order_creation_saga_reservations reservation
                SET status = 'RELEASED',
                    updated_at = CURRENT_TIMESTAMP
                FROM owned_saga
                WHERE reservation.saga_id = owned_saga.id
                  AND reservation.reservation_key = :reservationKey
                  AND reservation.status = 'RESERVED'
                """;

        MapSqlParameterSource parameters =
                baseParameters(
                        sagaId,
                        workerId,
                        fence,
                        leaseMs
                )
                        .addValue(
                                "reservationKey",
                                reservationKey
                        );

        requireSingleUpdate(
                jdbcTemplate.update(sql, parameters),
                sagaId,
                workerId,
                fence,
                "mark reservation RELEASED"
        );
    }

    @Transactional
    public void markCompensated(
            UUID sagaId,
            String workerId,
            long fence
    ) {
        String sql = """
                UPDATE order_creation_sagas
                SET status = 'COMPENSATED',
                    updated_at = CURRENT_TIMESTAMP,
                    recovery_owner = NULL,
                    recovery_lease_until = NULL
                WHERE id = :sagaId
                  AND recovery_owner = :workerId
                  AND recovery_fence = :fence
                  AND recovery_lease_until >= CURRENT_TIMESTAMP
                  AND status = 'COMPENSATING'
                """;

        MapSqlParameterSource parameters =
                new MapSqlParameterSource()
                        .addValue("sagaId", sagaId)
                        .addValue("workerId", workerId)
                        .addValue("fence", fence);

        requireSingleUpdate(
                jdbcTemplate.update(sql, parameters),
                sagaId,
                workerId,
                fence,
                "mark COMPENSATED"
        );
    }

    @Transactional
    public void markCompensationFailed(
            UUID sagaId,
            String workerId,
            long fence,
            long leaseMs,
            String reason
    ) {
        String sql = """
                UPDATE order_creation_sagas
                SET status = 'COMPENSATION_FAILED',
                    failure_reason = :reason,
                    updated_at = CURRENT_TIMESTAMP,
                    recovery_lease_until =
                        CURRENT_TIMESTAMP
                        + (:leaseMs * INTERVAL '1 millisecond')
                WHERE id = :sagaId
                  AND recovery_owner = :workerId
                  AND recovery_fence = :fence
                  AND recovery_lease_until >= CURRENT_TIMESTAMP
                  AND status = 'COMPENSATING'
                """;

        MapSqlParameterSource parameters =
                baseParameters(
                        sagaId,
                        workerId,
                        fence,
                        leaseMs
                )
                        .addValue(
                                "reason",
                                normalizeFailureReason(reason)
                        );

        requireSingleUpdate(
                jdbcTemplate.update(sql, parameters),
                sagaId,
                workerId,
                fence,
                "mark COMPENSATION_FAILED"
        );
    }

    private MapSqlParameterSource baseParameters(
            UUID sagaId,
            String workerId,
            long fence,
            long leaseMs
    ) {
        return new MapSqlParameterSource()
                .addValue("sagaId", sagaId)
                .addValue("workerId", workerId)
                .addValue("fence", fence)
                .addValue("leaseMs", leaseMs);
    }

    private void requireSingleUpdate(
            int updatedRows,
            UUID sagaId,
            String workerId,
            long fence,
            String operation
    ) {
        if (updatedRows == 1) {
            return;
        }

        throw new SagaRecoveryFencingException(
                "Fenced saga state update rejected: "
                        + "operation="
                        + operation
                        + ", sagaId="
                        + sagaId
                        + ", workerId="
                        + workerId
                        + ", fence="
                        + fence
        );
    }

    private String normalizeFailureReason(
            String reason
    ) {
        if (reason == null || reason.isBlank()) {
            return "Unknown saga recovery failure";
        }

        if (reason.length()
                <= MAX_FAILURE_REASON_LENGTH) {

            return reason;
        }

        return reason.substring(
                0,
                MAX_FAILURE_REASON_LENGTH
        );
    }
}