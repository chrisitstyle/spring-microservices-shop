package pl.chrisitstyle.order.saga;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class OrderCreationSagaClaimService {

    private static final List<String> RECOVERABLE_STATUSES =
            List.of(
                    OrderCreationSagaStatus.STARTED.name(),
                    OrderCreationSagaStatus.RESERVING_STOCK.name(),
                    OrderCreationSagaStatus.STOCK_RESERVED.name(),
                    OrderCreationSagaStatus.COMPLETING_ORDER.name(),
                    OrderCreationSagaStatus.COMPENSATING.name(),
                    OrderCreationSagaStatus.COMPENSATION_FAILED.name()
            );

    private final NamedParameterJdbcTemplate jdbcTemplate;

    @Transactional
    public List<SagaRecoveryClaim> claimRecoverable(
            String workerId,
            long staleAfterMs,
            long leaseMs,
            int batchSize
    ) {
        String sql = """
            WITH candidates AS (
                SELECT id
                FROM order_creation_sagas
                WHERE status IN (:statuses)
                  AND updated_at <
                      CURRENT_TIMESTAMP
                      - (:staleAfterMs * INTERVAL '1 millisecond')
                  AND (
                      recovery_lease_until IS NULL
                      OR recovery_lease_until < CURRENT_TIMESTAMP
                  )
                ORDER BY updated_at
                FOR UPDATE SKIP LOCKED
                LIMIT :batchSize
            )
            UPDATE order_creation_sagas saga
            SET recovery_owner = :workerId,
                recovery_lease_until =
                    CURRENT_TIMESTAMP
                    + (:leaseMs * INTERVAL '1 millisecond'),
                recovery_fence =
                    recovery_fence + 1
            FROM candidates
            WHERE saga.id = candidates.id
            RETURNING
                saga.id,
                saga.recovery_fence
            """;

        MapSqlParameterSource parameters =
                new MapSqlParameterSource()
                        .addValue("statuses", RECOVERABLE_STATUSES)
                        .addValue("workerId", workerId)
                        .addValue("staleAfterMs", staleAfterMs)
                        .addValue("leaseMs", leaseMs)
                        .addValue("batchSize", batchSize);

        return jdbcTemplate.query(
                sql,
                parameters,
                (resultSet, rowNumber) ->
                        new SagaRecoveryClaim(
                                resultSet.getObject(
                                        "id",
                                        UUID.class
                                ),
                                resultSet.getLong(
                                        "recovery_fence"
                                )
                        )
        );
    }

    @Transactional
    public boolean renewLease(
            UUID sagaId,
            String workerId,
            long fence,
            long leaseMs
    ) {
        String sql = """
            UPDATE order_creation_sagas
            SET recovery_lease_until =
                CURRENT_TIMESTAMP
                + (:leaseMs * INTERVAL '1 millisecond')
            WHERE id = :sagaId
              AND recovery_owner = :workerId
              AND recovery_fence = :fence
              AND recovery_lease_until >= CURRENT_TIMESTAMP
            """;

        MapSqlParameterSource parameters =
                new MapSqlParameterSource()
                        .addValue("sagaId", sagaId)
                        .addValue("workerId", workerId)
                        .addValue("fence", fence)
                        .addValue("leaseMs", leaseMs);

        return jdbcTemplate.update(
                sql,
                parameters
        ) == 1;
    }

    @Transactional
    public void releaseClaim(
            UUID sagaId,
            String workerId,
            long fence
    ) {
        String sql = """
            UPDATE order_creation_sagas
            SET recovery_owner = NULL,
                recovery_lease_until = NULL
            WHERE id = :sagaId
              AND recovery_owner = :workerId
              AND recovery_fence = :fence
            """;

        MapSqlParameterSource parameters =
                new MapSqlParameterSource()
                        .addValue("sagaId", sagaId)
                        .addValue("workerId", workerId)
                        .addValue("fence", fence);

        jdbcTemplate.update(
                sql,
                parameters
        );
    }
}
