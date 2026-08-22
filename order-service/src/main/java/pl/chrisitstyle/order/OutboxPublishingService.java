package pl.chrisitstyle.order;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.context.Scope;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.common.KafkaException;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import pl.chrisitstyle.order.observability.OutboxTraceLinker;

import java.util.concurrent.ExecutionException;

@Slf4j
@Service
@RequiredArgsConstructor
public class OutboxPublishingService {

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final OutboxStateService outboxStateService;
    private final OutboxTraceLinker outboxTraceLinker;

    public void publish(OutboxClaim event, String workerId) {
        Span span = outboxTraceLinker.startLinkedSpan(event);

        try (Scope ignored = span.makeCurrent()) {
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
            }

        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();

            recordFailure(span, exception);

            outboxStateService.release(event.id(), workerId);

            log.warn(
                    "Outbox publishing interrupted: eventId={}",
                    event.id()
            );

        } catch (ExecutionException exception) {
            recordFailure(span, exception);

            outboxStateService.release(event.id(), workerId);

            log.warn(
                    "Could not publish outbox event: eventId={}, aggregateId={}",
                    event.id(),
                    event.aggregateId(),
                    exception
            );

        } catch (KafkaException exception) {
            recordFailure(span, exception);

            outboxStateService.release(event.id(), workerId);

            log.warn(
                    "Kafka unavailable while publishing outbox event: eventId={}, aggregateId={}",
                    event.id(),
                    event.aggregateId(),
                    exception
            );

        } finally {
            span.end();
        }
    }

    private void recordFailure(Span span, Exception exception) {
        span.recordException(exception);
        span.setStatus(StatusCode.ERROR);
    }
}