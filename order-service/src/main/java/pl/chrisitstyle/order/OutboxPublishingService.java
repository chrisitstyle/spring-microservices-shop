package pl.chrisitstyle.order;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.util.concurrent.ExecutionException;

@Slf4j
@Service
@RequiredArgsConstructor
public class OutboxPublishingService {

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final OutboxStateService outboxStateService;

    public void publish(OutboxClaim event, String workerId) {
        try {
            kafkaTemplate.send(
                    event.topic(),
                    event.eventKey(),
                    event.payload()
            ).get();

            boolean markedAsPublished =
                    outboxStateService.markPublished(event.id(), workerId);

            if (markedAsPublished) {
                log.info(
                        "Outbox event published: eventId={}, eventType={}, aggregateId={}, workerId={}",
                        event.id(),
                        event.eventType(),
                        event.aggregateId(),
                        workerId
                );
            } else {
                log.warn(
                        "Outbox event was published but could not be marked as published: eventId={}",
                        event.id()
                );
            }

        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();

            outboxStateService.release(event.id(), workerId);

            log.warn(
                    "Outbox publishing interrupted: eventId={}",
                    event.id()
            );

        } catch (ExecutionException exception) {
            outboxStateService.release(event.id(), workerId);

            log.warn(
                    "Could not publish outbox event: eventId={}, aggregateId={}",
                    event.id(),
                    event.aggregateId(),
                    exception
            );
        }
    }
}
