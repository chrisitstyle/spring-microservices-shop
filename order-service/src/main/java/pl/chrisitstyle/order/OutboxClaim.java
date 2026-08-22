package pl.chrisitstyle.order;

import java.util.UUID;

public record OutboxClaim(
        UUID id,
        String eventType,
        Long aggregateId,
        String topic,
        String eventKey,
        String payload,
        String traceParent,
        String traceState
) {
}
