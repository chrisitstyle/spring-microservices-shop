package pl.chrisitstyle.notification;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

@Component
public class NotificationMetrics {

    private final Counter processedCounter;
    private final Counter duplicatesCounter;
    private final Counter dltCounter;

    public NotificationMetrics(MeterRegistry meterRegistry) {
        this.processedCounter = Counter.builder("notifications.processed")
                .description("Total number of successfully processed notifications")
                .register(meterRegistry);

        this.duplicatesCounter = Counter.builder("notifications.duplicates")
                .description("Total number of duplicate notifications")
                .register(meterRegistry);

        this.dltCounter = Counter.builder("notifications.dlt")
                .description("Total number of notifications sent to DLT")
                .register(meterRegistry);
    }

    public void incrementProcessed() {
        processedCounter.increment();
    }

    public void incrementDuplicates() {
        duplicatesCounter.increment();
    }

    public void incrementDlt() {
        dltCounter.increment();
    }
}
