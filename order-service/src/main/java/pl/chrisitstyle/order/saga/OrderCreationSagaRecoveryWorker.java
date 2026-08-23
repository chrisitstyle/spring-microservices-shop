package pl.chrisitstyle.order.saga;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class OrderCreationSagaRecoveryWorker {

    private final OrderCreationSagaClaimService claimService;
    private final OrderCreationSagaRecoveryService recoveryService;
    private final SagaRecoveryWorkerIdentity workerIdentity;

    @Value("${app.saga.recovery.stale-after-ms:120000}")
    private long staleAfterMs;

    @Value("${app.saga.recovery.lease-ms:120000}")
    private long leaseMs;

    @Value("${app.saga.recovery.batch-size:10}")
    private int batchSize;

    @Scheduled(
            initialDelayString =
                    "${app.saga.recovery.initial-delay-ms:30000}",
            fixedDelayString =
                    "${app.saga.recovery.fixed-delay-ms:30000}"
    )
    public void recoverInterruptedSagas() {
        String workerId =
                workerIdentity.value();

        List<UUID> sagaIds =
                claimService.claimRecoverable(
                        workerId,
                        staleAfterMs,
                        leaseMs,
                        batchSize
                );

        if (!sagaIds.isEmpty()) {
            log.info(
                    "Saga recovery worker claimed sagas: "
                            + "workerId={}, sagaIds={}",
                    workerId,
                    sagaIds
            );
        }

        for (UUID sagaId : sagaIds) {
            try {
                recoveryService.recover(
                        sagaId,
                        workerId,
                        leaseMs
                );

            } catch (RuntimeException exception) {

                log.error(
                        "Unexpected saga recovery failure: "
                                + "sagaId={}, workerId={}",
                        sagaId,
                        workerId,
                        exception
                );

            } finally {

                claimService.releaseClaim(
                        sagaId,
                        workerId
                );
            }
        }
    }
}