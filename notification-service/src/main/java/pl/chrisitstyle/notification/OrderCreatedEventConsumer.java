package pl.chrisitstyle.notification;

import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
// kafkalistener
public class OrderCreatedEventConsumer {

    @KafkaListener(topics = "order.created.v1")
    public void consume(OrderCreatedEvent event) {
        log.info(
                "Order created notification: orderId={}, userId={}, totalAmount={}",
                event.orderId(),
                event.userId(),
                event.totalAmount()
        );
    }
}
