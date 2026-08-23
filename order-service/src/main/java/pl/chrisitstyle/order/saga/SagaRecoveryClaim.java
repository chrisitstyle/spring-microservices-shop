package pl.chrisitstyle.order.saga;

import java.util.UUID;

public record SagaRecoveryClaim(
        UUID sagaId,
        long fence
) {
}
