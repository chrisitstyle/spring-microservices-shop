package pl.chrisitstyle.notification;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.DltHandler;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.annotation.RetryableTopic;
import org.springframework.stereotype.Component;
import org.springframework.kafka.annotation.BackOff;
import org.springframework.transaction.annotation.Transactional;
import pl.chrisitstyle.notification.exception.InvalidNotificationException;
import pl.chrisitstyle.notification.exception.NotificationProviderUnavailableException;

@Slf4j
@Component
@RequiredArgsConstructor
// kafkalistener
public class OrderCreatedEventConsumer {

    private final ProcessedEventRepository processedEventRepository;
    private final NotificationMetrics notificationMetrics;

    @Transactional
    @RetryableTopic(
            attempts = "3",
            backOff = @BackOff(delay = 2000),
            numPartitions = "3",
            replicationFactor = "1",
            include = NotificationProviderUnavailableException.class
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

        boolean firstProcessing =
                processedEventRepository.tryRegister(event.orderId());

        if (!firstProcessing) {
            notificationMetrics.incrementDuplicates();
            log.warn(
                    "Duplicate order created event ignored: orderId={}",
                    event.orderId()
            );
            return;
        }

        if (event.orderId() == 8L) {
            throw new NotificationProviderUnavailableException(
                    "Notification provider unavailable for order " + event.orderId()
            );
        }

        if (event.orderId() == 9L) {
            throw new InvalidNotificationException(
                    "Invalid notification data for order " + event.orderId()
            );
        }

        log.info(
                "Order created notification sent: orderId={}",
                event.orderId()
        );

        notificationMetrics.incrementProcessed();
    }

    @DltHandler
    public void handleDlt(OrderCreatedEvent event) {
        notificationMetrics.incrementDlt();
        log.error(
                "Order created event sent to DLT: orderId={}",
                event.orderId()
        );
    }
}
