package pl.chrisitstyle.shop.order;

import java.math.BigDecimal;

public record OrderItemResponse(
        Long productId,
        Integer quantity,
        BigDecimal unitPrice,
        BigDecimal totalPrice
) {
}
