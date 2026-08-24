package pl.chrisitstyle.order.saga;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import pl.chrisitstyle.order.exception.SagaRecoveryFencingException;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
@DataJpaTest(
        properties = {
                "spring.jpa.hibernate.ddl-auto=validate"
        }
)
@AutoConfigureTestDatabase(
        replace = AutoConfigureTestDatabase.Replace.NONE
)
@Import({
        OrderCreationSagaClaimService.class,
        OrderCreationSagaFencedStateService.class
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class OrderCreationSagaClaimIntegrationTest {

    private static final long STALE_AFTER_MS = 120_000;
    private static final long LEASE_MS = 60_000;

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:17");

    @Autowired
    private OrderCreationSagaClaimService claimService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private OrderCreationSagaFencedStateService fencedStateService;

    @BeforeEach
    void cleanDatabase() {

        jdbcTemplate.update(
                "DELETE FROM order_creation_saga_reservations"
        );

        jdbcTemplate.update(
                "DELETE FROM order_creation_sagas"
        );
    }

    @Test
    void shouldClaimOnlyStaleRecoverableSagas() {

        UUID staleStarted =
                insertSaga(
                        OrderCreationSagaStatus.STARTED,
                        Instant.now().minusSeconds(600)
                );

        UUID staleCompensationFailed =
                insertSaga(
                        OrderCreationSagaStatus.COMPENSATION_FAILED,
                        Instant.now().minusSeconds(600)
                );

        UUID freshStarted =
                insertSaga(
                        OrderCreationSagaStatus.STARTED,
                        Instant.now().minusSeconds(10)
                );

        UUID staleCompleted =
                insertSaga(
                        OrderCreationSagaStatus.COMPLETED,
                        Instant.now().minusSeconds(600)
                );

        UUID staleCompensated =
                insertSaga(
                        OrderCreationSagaStatus.COMPENSATED,
                        Instant.now().minusSeconds(600)
                );

        UUID staleFailed =
                insertSaga(
                        OrderCreationSagaStatus.FAILED,
                        Instant.now().minusSeconds(600)
                );

        List<SagaRecoveryClaim> claims =
                claimService.claimRecoverable(
                        "worker-A",
                        STALE_AFTER_MS,
                        LEASE_MS,
                        10
                );

        assertThat(claims)
                .extracting(SagaRecoveryClaim::sagaId)
                .containsExactlyInAnyOrder(
                        staleStarted,
                        staleCompensationFailed
                );

        assertThat(claims)
                .extracting(SagaRecoveryClaim::fence)
                .containsOnly(1L);

        assertClaimedBy(
                staleStarted,
                "worker-A",
                1L
        );

        assertClaimedBy(
                staleCompensationFailed,
                "worker-A",
                1L
        );

        assertNotClaimed(freshStarted);
        assertNotClaimed(staleCompleted);
        assertNotClaimed(staleCompensated);
        assertNotClaimed(staleFailed);
    }

    private UUID insertSaga(
            OrderCreationSagaStatus status,
            Instant updatedAt
    ) {

        UUID sagaId =
                UUID.randomUUID();

        jdbcTemplate.update(
                """
                INSERT INTO order_creation_sagas (
                    id,
                    user_id,
                    status,
                    created_at,
                    updated_at
                )
                VALUES (?, ?, ?, ?, ?)
                """,
                sagaId,
                123L,
                status.name(),
                Timestamp.from(updatedAt),
                Timestamp.from(updatedAt)
        );

        return sagaId;
    }

    private void assertClaimedBy(
            UUID sagaId,
            String workerId,
            long expectedFence
    ) {

        Map<String, Object> row =
                jdbcTemplate.queryForMap(
                        """
                        SELECT
                            recovery_owner,
                            recovery_lease_until,
                            recovery_fence
                        FROM order_creation_sagas
                        WHERE id = ?
                        """,
                        sagaId
                );

        assertThat(row.get("recovery_owner"))
                .isEqualTo(workerId);

        assertThat(row.get("recovery_lease_until"))
                .isNotNull();

        assertThat(
                ((Number) row.get("recovery_fence"))
                        .longValue()
        )
                .isEqualTo(expectedFence);
    }

    @Test
    void shouldAllowTakeoverOnlyAfterRecoveryLeaseExpires() {

        UUID sagaId =
                insertSaga(
                        OrderCreationSagaStatus.STARTED,
                        Instant.now().minusSeconds(600)
                );

        List<SagaRecoveryClaim> firstClaims =
                claimService.claimRecoverable(
                        "worker-A",
                        STALE_AFTER_MS,
                        LEASE_MS,
                        10
                );

        assertThat(firstClaims)
                .containsExactly(
                        new SagaRecoveryClaim(
                                sagaId,
                                1L
                        )
                );

        assertClaimedBy(
                sagaId,
                "worker-A",
                1L
        );

        List<SagaRecoveryClaim> secondWorkerBeforeExpiry =
                claimService.claimRecoverable(
                        "worker-B",
                        STALE_AFTER_MS,
                        LEASE_MS,
                        10
                );

        assertThat(secondWorkerBeforeExpiry)
                .isEmpty();

        assertClaimedBy(
                sagaId,
                "worker-A",
                1L
        );

        expireLease(sagaId);

        List<SagaRecoveryClaim> secondWorkerAfterExpiry =
                claimService.claimRecoverable(
                        "worker-B",
                        STALE_AFTER_MS,
                        LEASE_MS,
                        10
                );

        assertThat(secondWorkerAfterExpiry)
                .containsExactly(
                        new SagaRecoveryClaim(
                                sagaId,
                                2L
                        )
                );

        assertClaimedBy(
                sagaId,
                "worker-B",
                2L
        );
    }

    @Test
    void shouldSkipSagaLockedByAnotherRecoveryWorker()
            throws Exception {

        UUID oldestSaga =
                insertSaga(
                        OrderCreationSagaStatus.STARTED,
                        Instant.now().minusSeconds(900)
                );

        UUID secondOldestSaga =
                insertSaga(
                        OrderCreationSagaStatus.STARTED,
                        Instant.now().minusSeconds(600)
                );

        CountDownLatch firstWorkerHasClaim =
                new CountDownLatch(1);

        CountDownLatch allowFirstWorkerToCommit =
                new CountDownLatch(1);

        ExecutorService executor =
                Executors.newSingleThreadExecutor();

        try {

            Future<List<SagaRecoveryClaim>> firstWorker =
                    executor.submit(() -> {

                        TransactionTemplate transaction =
                                new TransactionTemplate(
                                        transactionManager
                                );

                        return transaction.execute(
                                status -> {

                                    List<SagaRecoveryClaim> claims =
                                            claimService.claimRecoverable(
                                                    "worker-A",
                                                    STALE_AFTER_MS,
                                                    LEASE_MS,
                                                    1
                                            );

                                    firstWorkerHasClaim.countDown();

                                    awaitLatch(
                                            allowFirstWorkerToCommit
                                    );

                                    return claims;
                                }
                        );
                    });

            assertThat(
                    firstWorkerHasClaim.await(
                            5,
                            TimeUnit.SECONDS
                    )
            ).isTrue();

            List<SagaRecoveryClaim> secondWorkerClaims =
                    claimService.claimRecoverable(
                            "worker-B",
                            STALE_AFTER_MS,
                            LEASE_MS,
                            1
                    );

            assertThat(secondWorkerClaims)
                    .containsExactly(
                            new SagaRecoveryClaim(
                                    secondOldestSaga,
                                    1L
                            )
                    );

            allowFirstWorkerToCommit.countDown();

            List<SagaRecoveryClaim> firstWorkerClaims =
                    firstWorker.get(
                            5,
                            TimeUnit.SECONDS
                    );

            assertThat(firstWorkerClaims)
                    .containsExactly(
                            new SagaRecoveryClaim(
                                    oldestSaga,
                                    1L
                            )
                    );

            assertClaimedBy(
                    oldestSaga,
                    "worker-A",
                    1L
            );

            assertClaimedBy(
                    secondOldestSaga,
                    "worker-B",
                    1L
            );

        } finally {

            allowFirstWorkerToCommit.countDown();

            executor.shutdownNow();
        }
    }

    private void expireLease(
            UUID sagaId
    ) {

        int updatedRows =
                jdbcTemplate.update(
                        """
                        UPDATE order_creation_sagas
                        SET recovery_lease_until =
                            CURRENT_TIMESTAMP
                            - INTERVAL '1 second'
                        WHERE id = ?
                        """,
                        sagaId
                );

        assertThat(updatedRows)
                .isEqualTo(1);
    }

    @Test
    void shouldRejectStateUpdateFromStaleWorkerAfterTakeover() {

        UUID sagaId =
                insertSaga(
                        OrderCreationSagaStatus.STARTED,
                        Instant.now().minusSeconds(600)
                );

        List<SagaRecoveryClaim> workerAClaims =
                claimService.claimRecoverable(
                        "worker-A",
                        STALE_AFTER_MS,
                        LEASE_MS,
                        1
                );

        SagaRecoveryClaim workerAClaim =
                workerAClaims.getFirst();

        assertThat(workerAClaim)
                .isEqualTo(
                        new SagaRecoveryClaim(
                                sagaId,
                                1L
                        )
                );

        expireLease(sagaId);

        List<SagaRecoveryClaim> workerBClaims =
                claimService.claimRecoverable(
                        "worker-B",
                        STALE_AFTER_MS,
                        LEASE_MS,
                        1
                );

        SagaRecoveryClaim workerBClaim =
                workerBClaims.getFirst();

        assertThat(workerBClaim)
                .isEqualTo(
                        new SagaRecoveryClaim(
                                sagaId,
                                2L
                        )
                );

        assertClaimedBy(
                sagaId,
                "worker-B",
                2L
        );

        assertThatThrownBy(
                () -> fencedStateService.markCompensating(
                        sagaId,
                        "worker-A",
                        1L,
                        LEASE_MS,
                        "Failure reported by stale worker"
                )
        )
                .isInstanceOf(
                        SagaRecoveryFencingException.class
                )
                .hasMessageContaining(
                        "Fenced saga state update rejected"
                )
                .hasMessageContaining(
                        "workerId=worker-A"
                )
                .hasMessageContaining(
                        "fence=1"
                );

        assertSagaState(
                sagaId,
                OrderCreationSagaStatus.STARTED,
                "worker-B",
                2L
        );

        fencedStateService.markCompensating(
                sagaId,
                "worker-B",
                2L,
                LEASE_MS,
                "Recovery compensation"
        );

        assertSagaState(
                sagaId,
                OrderCreationSagaStatus.COMPENSATING,
                "worker-B",
                2L
        );
    }

    private void assertSagaState(
            UUID sagaId,
            OrderCreationSagaStatus expectedStatus,
            String expectedOwner,
            long expectedFence
    ) {

        Map<String, Object> row =
                jdbcTemplate.queryForMap(
                        """
                        SELECT
                            status,
                            recovery_owner,
                            recovery_fence
                        FROM order_creation_sagas
                        WHERE id = ?
                        """,
                        sagaId
                );

        assertThat(row.get("status"))
                .isEqualTo(
                        expectedStatus.name()
                );

        assertThat(row.get("recovery_owner"))
                .isEqualTo(expectedOwner);

        assertThat(
                ((Number) row.get("recovery_fence"))
                        .longValue()
        )
                .isEqualTo(expectedFence);
    }

    private void awaitLatch(
            CountDownLatch latch
    ) {

        try {

            boolean completed =
                    latch.await(
                            5,
                            TimeUnit.SECONDS
                    );

            if (!completed) {
                throw new IllegalStateException(
                        "Timed out while waiting for latch"
                );
            }

        } catch (InterruptedException exception) {

            Thread.currentThread().interrupt();

            throw new IllegalStateException(
                    "Thread interrupted while waiting",
                    exception
            );
        }
    }

    private void assertNotClaimed(
            UUID sagaId
    ) {

        Map<String, Object> row =
                jdbcTemplate.queryForMap(
                        """
                        SELECT
                            recovery_owner,
                            recovery_lease_until,
                            recovery_fence
                        FROM order_creation_sagas
                        WHERE id = ?
                        """,
                        sagaId
                );

        assertThat(row.get("recovery_owner"))
                .isNull();

        assertThat(row.get("recovery_lease_until"))
                .isNull();

        assertThat(
                ((Number) row.get("recovery_fence"))
                        .longValue()
        )
                .isZero();
    }


}