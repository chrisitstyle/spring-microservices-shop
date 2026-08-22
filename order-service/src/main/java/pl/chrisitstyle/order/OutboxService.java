package pl.chrisitstyle.order;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import pl.chrisitstyle.order.observability.TraceContextCapture;
import pl.chrisitstyle.order.observability.TraceContextSnapshot;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class OutboxService {

    private final OutboxEventRepository outboxEventRepository;
    private final JsonMapper jsonMapper;
    private final TraceContextCapture traceContextCapture;

    public void saveOrderCreatedEvent(OrderCreatedEvent event) {
        TraceContextSnapshot traceContext = traceContextCapture.capture();

        OutboxEvent outboxEvent = new OutboxEvent();
        outboxEvent.setId(UUID.randomUUID());
        outboxEvent.setAggregateType("ORDER");
        outboxEvent.setAggregateId(event.orderId());
        outboxEvent.setEventType("OrderCreated");
        outboxEvent.setTopic(KafkaTopicConfig.ORDER_CREATED_TOPIC);
        outboxEvent.setEventKey(event.orderId().toString());
        outboxEvent.setPayload(toJson(event));
        outboxEvent.setTraceParent(traceContext.traceParent());
        outboxEvent.setTraceState(traceContext.traceState());

        outboxEventRepository.save(outboxEvent);
    }

    private String toJson(OrderCreatedEvent event) {
        try {
            return jsonMapper.writeValueAsString(event);
        } catch (JacksonException exception) {
            throw new IllegalStateException(
                    "Could not serialize OrderCreatedEvent",
                    exception
            );
        }
    }
}
