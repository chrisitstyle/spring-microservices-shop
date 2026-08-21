package pl.chrisitstyle.product;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

public record StoredStockReservation(
        UUID idempotencyKey,
        Long productId,
        Integer quantity,
        BigDecimal unitPrice,
        OffsetDateTime releasedAt
) {
}
