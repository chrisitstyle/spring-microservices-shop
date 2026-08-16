package pl.chrisitstyle.product;

import java.math.BigDecimal;

public record ProductReservationResponse(
        Long productId,
        Integer quantity,
        BigDecimal unitPrice
) {
}
