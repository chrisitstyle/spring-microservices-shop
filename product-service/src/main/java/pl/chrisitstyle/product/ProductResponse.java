package pl.chrisitstyle.product;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

public record ProductResponse(
        Long id,
        String name,
        String description,
        BigDecimal price,
        Integer stockQuantity,
        Boolean active,
        OffsetDateTime createdAt
) {
}
