package pl.chrisitstyle.product;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class StockReservationRequestRepository {

    private final JdbcTemplate jdbcTemplate;

    public boolean tryRegister(
            UUID idempotencyKey,
            Long productId,
            Integer quantity
    ) {
        int insertedRows = jdbcTemplate.update("""
                INSERT INTO stock_reservation_requests (
                    idempotency_key,
                    product_id,
                    quantity
                )
                VALUES (?, ?, ?)
                ON CONFLICT (idempotency_key) DO NOTHING
                """,
                idempotencyKey,
                productId,
                quantity
        );

        return insertedRows == 1;
    }

    public Optional<StoredStockReservation> findByIdempotencyKey(
            UUID idempotencyKey
    ) {
        return jdbcTemplate.query("""
                        SELECT idempotency_key,
                               product_id,
                               quantity,
                               unit_price,
                               released_at
                        FROM stock_reservation_requests
                        WHERE idempotency_key = ?
                        """,
                resultSet -> {
                    if (!resultSet.next()) {
                        return Optional.empty();
                    }

                    return Optional.of(
                            new StoredStockReservation(
                                    resultSet.getObject(
                                            "idempotency_key",
                                            UUID.class
                                    ),
                                    resultSet.getLong("product_id"),
                                    resultSet.getInt("quantity"),
                                    resultSet.getBigDecimal("unit_price"),
                                    resultSet.getObject(
                                            "released_at",
                                            OffsetDateTime.class
                                    )
                            )
                    );
                },
                idempotencyKey
        );
    }

    public void saveResult(
            UUID idempotencyKey,
            BigDecimal unitPrice
    ) {
        jdbcTemplate.update("""
                UPDATE stock_reservation_requests
                SET unit_price = ?
                WHERE idempotency_key = ?
                """,
                unitPrice,
                idempotencyKey
        );
    }

    public Optional<StoredStockReservation> findForUpdate(
            UUID idempotencyKey
    ) {
        return jdbcTemplate.query("""
                        SELECT idempotency_key,
                               product_id,
                               quantity,
                               unit_price,
                               released_at
                        FROM stock_reservation_requests
                        WHERE idempotency_key = ?
                        FOR UPDATE
                        """,
                resultSet -> {
                    if (!resultSet.next()) {
                        return Optional.empty();
                    }

                    return Optional.of(
                            new StoredStockReservation(
                                    resultSet.getObject(
                                            "idempotency_key",
                                            UUID.class
                                    ),
                                    resultSet.getLong("product_id"),
                                    resultSet.getInt("quantity"),
                                    resultSet.getBigDecimal("unit_price"),
                                    resultSet.getObject(
                                            "released_at",
                                            OffsetDateTime.class
                                    )
                            )
                    );
                },
                idempotencyKey
        );
    }

    public void markReleased(
            UUID idempotencyKey,
            OffsetDateTime releasedAt
    ) {
        jdbcTemplate.update("""
                UPDATE stock_reservation_requests
                SET released_at = ?
                WHERE idempotency_key = ?
                """,
                releasedAt,
                idempotencyKey
        );
    }
}