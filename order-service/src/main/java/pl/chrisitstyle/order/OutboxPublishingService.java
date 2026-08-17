package pl.chrisitstyle.order;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.concurrent.ExecutionException;

@Slf4j
@Service
@RequiredArgsConstructor
public class OutboxPublishingService {

    private final OutboxEventRepository outboxEventRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;

    @Transactional
    public void publish(UUID eventId) {
        OutboxEvent event = outboxEventRepository.findById(eventId)
                .orElseThrow();

        if (event.getPublishedAt() != null) {
            return;
        }

        try {
            kafkaTemplate.send(
                    event.getTopic(),
                    event.getEventKey(),
                    event.getPayload()
            ).get();

            event.setPublishedAt(OffsetDateTime.now());

            log.info(
                    "Outbox event published: eventId={}, eventType={}, aggregateId={}",
                    event.getId(),
                    event.getEventType(),
                    event.getAggregateId()
            );
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();

            throw new IllegalStateException(
                    "Interrupted while publishing outbox event " + eventId,
                    exception
            );
        } catch (ExecutionException exception) {
            throw new IllegalStateException(
                    "Could not publish outbox event " + eventId,
                    exception
            );
        }
    }
}
