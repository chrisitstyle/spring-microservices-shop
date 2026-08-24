package pl.chrisitstyle.product;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import pl.chrisitstyle.product.domain.Product;
import pl.chrisitstyle.product.exception.IdempotencyConflictException;

import java.math.BigDecimal;
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
        ProductService.class,
        StockReservationRequestRepository.class
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ProductServiceIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:17");

    @Autowired
    private ProductService productService;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private StockReservationRequestRepository
            stockReservationRequestRepository;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @BeforeEach
    void cleanDatabase() {

        jdbcTemplate.update(
                "DELETE FROM stock_reservation_requests"
        );

        jdbcTemplate.update(
                "DELETE FROM products"
        );
    }

    @Test
    void shouldReserveStockAndPersistReservation() {

        Product product =
                createProduct(
                        "Test Laptop",
                        new BigDecimal("1999.99"),
                        10
                );

        Product savedProduct =
                productRepository.saveAndFlush(
                        product
                );

        Long productId =
                savedProduct.getId();

        UUID reservationKey =
                UUID.randomUUID();

        ProductReservationResponse response =
                productService.reserve(
                        productId,
                        new StockRequest(3),
                        reservationKey
                );

        entityManager.clear();

        Product productAfterReservation =
                productRepository.findById(
                                productId
                        )
                        .orElseThrow();

        StoredStockReservation storedReservation =
                stockReservationRequestRepository
                        .findByIdempotencyKey(
                                reservationKey
                        )
                        .orElseThrow();

        assertThat(response.productId())
                .isEqualTo(productId);

        assertThat(response.quantity())
                .isEqualTo(3);

        assertThat(response.unitPrice())
                .isEqualByComparingTo(
                        new BigDecimal("1999.99")
                );

        assertThat(productAfterReservation.getStockQuantity())
                .isEqualTo(7);

        assertThat(storedReservation.idempotencyKey())
                .isEqualTo(reservationKey);

        assertThat(storedReservation.productId())
                .isEqualTo(productId);

        assertThat(storedReservation.quantity())
                .isEqualTo(3);

        assertThat(storedReservation.unitPrice())
                .isEqualByComparingTo(
                        new BigDecimal("1999.99")
                );

        assertThat(storedReservation.releasedAt())
                .isNull();
    }

    @Test
    void shouldReturnPreviousReservationWithoutDecreasingStockAgain() {

        Product product =
                createProduct(
                        "Test Laptop",
                        new BigDecimal("1999.99"),
                        10
                );

        Product savedProduct =
                productRepository.saveAndFlush(
                        product
                );

        Long productId =
                savedProduct.getId();

        UUID reservationKey =
                UUID.randomUUID();

        ProductReservationResponse firstResponse =
                productService.reserve(
                        productId,
                        new StockRequest(3),
                        reservationKey
                );

        ProductReservationResponse replayResponse =
                productService.reserve(
                        productId,
                        new StockRequest(3),
                        reservationKey
                );

        entityManager.clear();

        Product productAfterReplay =
                productRepository.findById(
                                productId
                        )
                        .orElseThrow();

        StoredStockReservation storedReservation =
                stockReservationRequestRepository
                        .findByIdempotencyKey(
                                reservationKey
                        )
                        .orElseThrow();

        Integer reservationCount =
                jdbcTemplate.queryForObject(
                        """
                        SELECT COUNT(*)
                        FROM stock_reservation_requests
                        WHERE idempotency_key = ?
                        """,
                        Integer.class,
                        reservationKey
                );

        assertThat(firstResponse.productId())
                .isEqualTo(productId);

        assertThat(firstResponse.quantity())
                .isEqualTo(3);

        assertThat(firstResponse.unitPrice())
                .isEqualByComparingTo(
                        new BigDecimal("1999.99")
                );

        assertThat(replayResponse.productId())
                .isEqualTo(
                        firstResponse.productId()
                );

        assertThat(replayResponse.quantity())
                .isEqualTo(
                        firstResponse.quantity()
                );

        assertThat(replayResponse.unitPrice())
                .isEqualByComparingTo(
                        firstResponse.unitPrice()
                );

        assertThat(productAfterReplay.getStockQuantity())
                .isEqualTo(7);

        assertThat(reservationCount)
                .isEqualTo(1);

        assertThat(storedReservation.productId())
                .isEqualTo(productId);

        assertThat(storedReservation.quantity())
                .isEqualTo(3);

        assertThat(storedReservation.unitPrice())
                .isEqualByComparingTo(
                        new BigDecimal("1999.99")
                );

        assertThat(storedReservation.releasedAt())
                .isNull();
    }

    private Product createProduct(
            String name,
            BigDecimal price,
            int stockQuantity
    ) {

        Product product =
                new Product();

        product.setName(name);
        product.setDescription(
                "Product created by integration test"
        );
        product.setPrice(price);
        product.setStockQuantity(stockQuantity);
        product.setActive(true);

        return product;
    }

    @Test
    void shouldReleaseReservedStockOnlyOnce() {

        Product product =
                createProduct(
                        "Test Laptop",
                        new BigDecimal("1999.99"),
                        10
                );

        Product savedProduct =
                productRepository.saveAndFlush(
                        product
                );

        Long productId =
                savedProduct.getId();

        UUID reservationKey =
                UUID.randomUUID();

        productService.reserve(
                productId,
                new StockRequest(3),
                reservationKey
        );

        productService.release(
                productId,
                new StockRequest(3),
                reservationKey
        );

        entityManager.clear();

        Product productAfterFirstRelease =
                productRepository.findById(
                                productId
                        )
                        .orElseThrow();

        StoredStockReservation reservationAfterFirstRelease =
                stockReservationRequestRepository
                        .findByIdempotencyKey(
                                reservationKey
                        )
                        .orElseThrow();

        assertThat(productAfterFirstRelease.getStockQuantity())
                .isEqualTo(10);

        assertThat(reservationAfterFirstRelease.releasedAt())
                .isNotNull();

        var firstReleasedAt =
                reservationAfterFirstRelease.releasedAt();

        productService.release(
                productId,
                new StockRequest(3),
                reservationKey
        );

        entityManager.clear();

        Product productAfterReplay =
                productRepository.findById(
                                productId
                        )
                        .orElseThrow();

        StoredStockReservation reservationAfterReplay =
                stockReservationRequestRepository
                        .findByIdempotencyKey(
                                reservationKey
                        )
                        .orElseThrow();

        assertThat(productAfterReplay.getStockQuantity())
                .isEqualTo(10);

        assertThat(reservationAfterReplay.releasedAt())
                .isEqualTo(firstReleasedAt);
    }

    @Test
    void shouldRejectReleaseWithMismatchedQuantityWithoutChangingStock() {

        Product product =
                createProduct(
                        "Test Laptop",
                        new BigDecimal("1999.99"),
                        10
                );

        Product savedProduct =
                productRepository.saveAndFlush(product);

        Long productId =
                savedProduct.getId();

        UUID reservationKey =
                UUID.randomUUID();

        productService.reserve(
                productId,
                new StockRequest(3),
                reservationKey
        );

        assertThatThrownBy(
                () -> productService.release(
                        productId,
                        new StockRequest(5),
                        reservationKey
                )
        )
                .isInstanceOf(
                        IdempotencyConflictException.class
                )
                .hasMessage(
                        "Reservation key does not match stock release request"
                );

        entityManager.clear();

        Product productAfterConflict =
                productRepository.findById(productId)
                        .orElseThrow();

        StoredStockReservation storedReservation =
                stockReservationRequestRepository
                        .findByIdempotencyKey(reservationKey)
                        .orElseThrow();

        assertThat(productAfterConflict.getStockQuantity())
                .isEqualTo(7);

        assertThat(storedReservation.quantity())
                .isEqualTo(3);

        assertThat(storedReservation.releasedAt())
                .isNull();
    }

    @Test
    void shouldBlockConcurrentTransactionWhenReservationIsLockedForUpdate()
            throws Exception {

        Product product =
                createProduct(
                        "Test Laptop",
                        new BigDecimal("1999.99"),
                        10
                );

        Product savedProduct =
                productRepository.saveAndFlush(product);

        Long productId =
                savedProduct.getId();

        UUID reservationKey =
                UUID.randomUUID();

        productService.reserve(
                productId,
                new StockRequest(3),
                reservationKey
        );

        CountDownLatch firstLockAcquired =
                new CountDownLatch(1);

        CountDownLatch releaseFirstTransaction =
                new CountDownLatch(1);

        ExecutorService executor =
                Executors.newFixedThreadPool(2);

        try {

            Future<?> firstTransaction =
                    executor.submit(() -> {

                        TransactionTemplate transaction =
                                new TransactionTemplate(
                                        transactionManager
                                );

                        transaction.executeWithoutResult(
                                status -> {

                                    stockReservationRequestRepository
                                            .findForUpdate(
                                                    reservationKey
                                            )
                                            .orElseThrow();

                                    firstLockAcquired.countDown();

                                    awaitLatch(
                                            releaseFirstTransaction
                                    );
                                }
                        );
                    });

            assertThat(
                    firstLockAcquired.await(
                            5,
                            TimeUnit.SECONDS
                    )
            ).isTrue();

            Future<Throwable> secondTransaction =
                    executor.submit(() -> {

                        try {

                            TransactionTemplate transaction =
                                    new TransactionTemplate(
                                            transactionManager
                                    );

                            transaction.executeWithoutResult(
                                    status -> {

                                        jdbcTemplate.execute(
                                                """
                                                SET LOCAL lock_timeout = '500ms'
                                                """
                                        );

                                        stockReservationRequestRepository
                                                .findForUpdate(
                                                        reservationKey
                                                )
                                                .orElseThrow();
                                    }
                            );

                            return null;

                        } catch (Throwable exception) {

                            return exception;
                        }
                    });

            Throwable secondTransactionFailure =
                    secondTransaction.get(
                            5,
                            TimeUnit.SECONDS
                    );

            assertThat(secondTransactionFailure)
                    .isNotNull()
                    .isInstanceOf(
                            DataAccessException.class
                    )
                    .hasStackTraceContaining(
                            "lock timeout"
                    );

            releaseFirstTransaction.countDown();

            firstTransaction.get(
                    5,
                    TimeUnit.SECONDS
            );

        } finally {

            releaseFirstTransaction.countDown();

            executor.shutdownNow();
        }
    }

    @Test
    void shouldReleaseStockOnlyOnceWhenTwoRequestsRunConcurrently()
            throws Exception {

        Product product =
                createProduct(
                        "Test Laptop",
                        new BigDecimal("1999.99"),
                        10
                );

        Product savedProduct =
                productRepository.saveAndFlush(product);

        Long productId =
                savedProduct.getId();

        UUID reservationKey =
                UUID.randomUUID();

        productService.reserve(
                productId,
                new StockRequest(3),
                reservationKey
        );

        CountDownLatch workersReady =
                new CountDownLatch(2);

        CountDownLatch start =
                new CountDownLatch(1);

        ExecutorService executor =
                Executors.newFixedThreadPool(2);

        try {

            Future<?> firstRelease =
                    executor.submit(
                            () -> {

                                workersReady.countDown();

                                awaitLatch(start);

                                productService.release(
                                        productId,
                                        new StockRequest(3),
                                        reservationKey
                                );
                            }
                    );

            Future<?> secondRelease =
                    executor.submit(
                            () -> {

                                workersReady.countDown();

                                awaitLatch(start);

                                productService.release(
                                        productId,
                                        new StockRequest(3),
                                        reservationKey
                                );
                            }
                    );

            assertThat(
                    workersReady.await(
                            5,
                            TimeUnit.SECONDS
                    )
            ).isTrue();

            start.countDown();

            firstRelease.get(
                    5,
                    TimeUnit.SECONDS
            );

            secondRelease.get(
                    5,
                    TimeUnit.SECONDS
            );

        } finally {

            start.countDown();

            executor.shutdownNow();
        }

        entityManager.clear();

        Product productAfterConcurrentRelease =
                productRepository.findById(
                                productId
                        )
                        .orElseThrow();

        StoredStockReservation storedReservation =
                stockReservationRequestRepository
                        .findByIdempotencyKey(
                                reservationKey
                        )
                        .orElseThrow();

        assertThat(
                productAfterConcurrentRelease
                        .getStockQuantity()
        )
                .isEqualTo(10);

        assertThat(
                storedReservation.releasedAt()
        )
                .isNotNull();
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
}