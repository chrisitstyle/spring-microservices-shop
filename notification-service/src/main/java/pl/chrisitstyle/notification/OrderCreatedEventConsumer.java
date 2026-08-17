package pl.chrisitstyle.notification;

import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.DltHandler;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.annotation.RetryableTopic;
import org.springframework.stereotype.Component;
import org.springframework.kafka.annotation.BackOff;
@Slf4j
@Component
// kafkalistener
public class OrderCreatedEventConsumer {
    @RetryableTopic(
            attempts = "3",
            backOff = @BackOff(delay = 2000)
    )
    @KafkaListener(
            topics = "order.created.v1",
            groupId = "notification-service"
    )
    public void consume(OrderCreatedEvent event) {
        log.info(
                "Processing order created event: orderId={}, userId={}, totalAmount={}",
                event.orderId(),
                event.userId(),
                event.totalAmount()
        );

        if (event.orderId() == 7L) {
            throw new RuntimeException(
                    "Simulated notification failure for order " + event.orderId()
            );
        }

        log.info(
                "Order created notification sent: orderId={}",
                event.orderId()
        );
    }

    @DltHandler
    public void handleDlt(OrderCreatedEvent event) {
        log.error(
                "Order created event sent to DLT: orderId={}",
                event.orderId()
        );
    }
}
