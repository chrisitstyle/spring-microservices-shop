package pl.chrisitstyle.order;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class OutboxClaimService {

    private final OutboxEventRepository outboxEventRepository;

    @Transactional
    public List<OutboxClaim> claimBatch(String workerId, int batchSize) {

        int releasedEvents = outboxEventRepository.releaseStaleEvents();

        if (releasedEvents > 0) {
            log.warn(
                    "Released stale outbox events: count={}",
                    releasedEvents
            );
        }

        List<OutboxEvent> events =
                outboxEventRepository.findPendingForProcessing(batchSize);

        OffsetDateTime now = OffsetDateTime.now();

        events.forEach(event -> {
            event.setStatus(OutboxStatus.PROCESSING);
            event.setLockedAt(now);
            event.setLockedBy(workerId);
        });

        return events.stream()
                .map(event -> new OutboxClaim(
                        event.getId(),
                        event.getEventType(),
                        event.getAggregateId(),
                        event.getTopic(),
                        event.getEventKey(),
                        event.getPayload()
                ))
                .toList();
    }
}
