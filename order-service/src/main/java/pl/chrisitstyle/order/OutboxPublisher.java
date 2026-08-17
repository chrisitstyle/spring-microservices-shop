package pl.chrisitstyle.order;

import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class OutboxPublisher {

    private static final int BATCH_SIZE = 100;

    private final OutboxClaimService outboxClaimService;
    private final OutboxPublishingService outboxPublishingService;

    private final String workerId = UUID.randomUUID().toString();

    @Scheduled(fixedDelayString = "${outbox.publisher.fixed-delay:2000}")
    public void publishPendingEvents() {

        List<OutboxClaim> events =
                outboxClaimService.claimBatch(workerId, BATCH_SIZE);

        events.forEach(event ->
                outboxPublishingService.publish(event, workerId)
        );
    }
}
