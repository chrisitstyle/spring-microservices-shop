package pl.chrisitstyle.order;

import java.math.BigDecimal;

public record ProductReservationResponse(
        Long productId,
        Integer quantity,
        BigDecimal unitPrice
) {
}
