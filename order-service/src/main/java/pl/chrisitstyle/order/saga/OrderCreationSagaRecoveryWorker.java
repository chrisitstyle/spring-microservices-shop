package pl.chrisitstyle.order.saga;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class OrderCreationSagaRecoveryWorker {

    private final OrderCreationSagaStateService sagaStateService;
    private final OrderCreationSagaRecoveryService recoveryService;

    @Value("${app.saga.recovery.stale-after-ms:120000}")
    private long staleAfterMs;

    @Scheduled(
            initialDelayString =
                    "${app.saga.recovery.initial-delay-ms:30000}",
            fixedDelayString =
                    "${app.saga.recovery.fixed-delay-ms:30000}"
    )
    public void recoverInterruptedSagas() {
        Instant staleBefore =
                Instant.now()
                        .minusMillis(staleAfterMs);

        List<UUID> sagaIds =
                sagaStateService.findRecoverableSagaIds(
                        staleBefore
                );

        for (UUID sagaId : sagaIds) {
            try {
                recoveryService.recover(sagaId);

            } catch (RuntimeException exception) {
                log.error(
                        "Unexpected saga recovery failure: sagaId={}",
                        sagaId,
                        exception
                );
            }
        }
    }
}
