package pl.chrisitstyle.order;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class OutboxService {

    private final OutboxEventRepository outboxEventRepository;
    private final JsonMapper jsonMapper;

    public void saveOrderCreatedEvent(OrderCreatedEvent event) {
        OutboxEvent outboxEvent = new OutboxEvent();

        outboxEvent.setId(UUID.randomUUID());
        outboxEvent.setAggregateType("ORDER");
        outboxEvent.setAggregateId(event.orderId());
        outboxEvent.setEventType("OrderCreated");
        outboxEvent.setTopic(KafkaTopicConfig.ORDER_CREATED_TOPIC);
        outboxEvent.setEventKey(event.orderId().toString());
        outboxEvent.setPayload(toJson(event));

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
