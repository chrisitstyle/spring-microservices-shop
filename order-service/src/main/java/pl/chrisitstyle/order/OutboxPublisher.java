package pl.chrisitstyle.order;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class OutboxPublisher {

    private final OutboxEventRepository outboxEventRepository;
    private final OutboxPublishingService outboxPublishingService;

    @Scheduled(fixedDelayString = "${outbox.publisher.fixed-delay:2000}")
    public void publishPendingEvents() {
        outboxEventRepository
                .findTop100ByPublishedAtIsNullOrderByCreatedAtAsc()
                .forEach(event ->
                        outboxPublishingService.publish(event.getId())
                );
    }
}
