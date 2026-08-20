package pl.chrisitstyle.order;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class OutboxMetrics implements MeterBinder {

    private final OutboxEventRepository outboxEventRepository;

    @Override
    public void bindTo(@NonNull MeterRegistry registry) {

        Gauge.builder(
                        "outbox.pending.events",
                        outboxEventRepository,
                        repository -> repository.countByStatus(OutboxStatus.PENDING)
                )
                .description("Number of pending outbox events")
                .register(registry);

        Gauge.builder(
                        "outbox.processing.events",
                        outboxEventRepository,
                        repository -> repository.countByStatus(OutboxStatus.PROCESSING)
                )
                .description("Number of outbox events currently being processed")
                .register(registry);
    }
}
