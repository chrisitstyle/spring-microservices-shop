package pl.chrisitstyle.order;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

public record OrderCreatedEvent(
        Long orderId,
        Long userId,
        BigDecimal totalAmount,
        OffsetDateTime createdAt
) {
}
