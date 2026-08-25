package pl.chrisitstyle.product;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import pl.chrisitstyle.product.domain.Product;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@SpringBootTest
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ProductCacheIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>(
                    "postgres:17"
            );

    @Container
    @ServiceConnection(name = "redis")
    static final GenericContainer<?> redis =
            new GenericContainer<>(
                    "redis:8-alpine"
            )
                    .withExposedPorts(
                            6379
                    );


    @Autowired
    private ProductService productService;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;


    @BeforeEach
    void cleanState() throws Exception {

        var ping =
                redis.execInContainer(
                        "redis-cli",
                        "ping"
                );

        assertThat(
                ping.getExitCode()
        )
                .as("Redis Testcontainer should be available")
                .isZero();

        assertThat(
                ping.getStdout().trim()
        )
                .isEqualTo("PONG");


        var flush =
                redis.execInContainer(
                        "redis-cli",
                        "FLUSHDB"
                );

        assertThat(
                flush.getExitCode()
        )
                .as("Redis database should be flushed")
                .isZero();


        jdbcTemplate.update(
                "DELETE FROM stock_reservation_requests"
        );

        productRepository.deleteAll();
    }


    @Test
    void shouldReturnCachedProductOnSecondRead() {

        Product savedProduct =
                productRepository.saveAndFlush(
                        createProduct(
                                "Cached Laptop",
                                10
                        )
                );

        Long productId =
                savedProduct.getId();


        // First read loads the product from PostgreSQL
        // and should put it into Redis.
        ProductResponse firstResponse =
                productService.getById(
                        productId
                );

        assertThat(
                firstResponse.description()
        )
                .isEqualTo(
                        "Original description"
                );


        // Change PostgreSQL directly, bypassing ProductService
        // and therefore bypassing cache eviction.
        jdbcTemplate.update(
                """
                UPDATE products
                SET description = ?
                WHERE id = ?
                """,
                "Changed directly in PostgreSQL",
                productId
        );


        // If Redis cache works, the second read must still return
        // the old cached value.
        ProductResponse secondResponse =
                productService.getById(
                        productId
                );

        assertThat(
                secondResponse.description()
        )
                .as("Second read should come from Redis cache")
                .isEqualTo(
                        "Original description"
                );


        // PostgreSQL itself really contains the changed value.
        String descriptionInDatabase =
                jdbcTemplate.queryForObject(
                        """
                        SELECT description
                        FROM products
                        WHERE id = ?
                        """,
                        String.class,
                        productId
                );

        assertThat(
                descriptionInDatabase
        )
                .isEqualTo(
                        "Changed directly in PostgreSQL"
                );
    }


    @Test
    void shouldEvictCacheAfterProductUpdate() {

        Product savedProduct =
                productRepository.saveAndFlush(
                        createProduct(
                                "Update Laptop",
                                10
                        )
                );

        Long productId =
                savedProduct.getId();


        // Populate Redis.
        ProductResponse firstResponse =
                productService.getById(
                        productId
                );

        assertThat(
                firstResponse.description()
        )
                .isEqualTo(
                        "Original description"
                );


        // Prove that the cached value is really being used.
        jdbcTemplate.update(
                """
                UPDATE products
                SET description = ?
                WHERE id = ?
                """,
                "Changed directly in PostgreSQL",
                productId
        );


        ProductResponse cachedResponse =
                productService.getById(
                        productId
                );

        assertThat(
                cachedResponse.description()
        )
                .as("Value should still come from Redis before update")
                .isEqualTo(
                        "Original description"
                );


        // ProductService.update() should update PostgreSQL
        // and evict the cached product.
        productService.update(
                productId,
                new UpdateProductRequest(
                        "Update Laptop",
                        "Updated description",
                        new BigDecimal("1999.99"),
                        10,
                        true
                )
        );


        String descriptionInDatabase =
                jdbcTemplate.queryForObject(
                        """
                        SELECT description
                        FROM products
                        WHERE id = ?
                        """,
                        String.class,
                        productId
                );

        assertThat(
                descriptionInDatabase
        )
                .as("Product should be updated in PostgreSQL")
                .isEqualTo(
                        "Updated description"
                );


        // If @CacheEvict worked, this read cannot return
        // "Original description" anymore.
        ProductResponse responseAfterUpdate =
                productService.getById(
                        productId
                );

        assertThat(
                responseAfterUpdate.description()
        )
                .as("GET after update should load the new value")
                .isEqualTo(
                        "Updated description"
                );
    }


    @Test
    void shouldEvictCacheAfterReserveAndRelease() {

        Product savedProduct =
                productRepository.saveAndFlush(
                        createProduct(
                                "Stock Laptop",
                                10
                        )
                );

        Long productId =
                savedProduct.getId();

        UUID reservationKey =
                UUID.randomUUID();


        // Populate Redis with stock = 10.
        ProductResponse beforeReservation =
                productService.getById(
                        productId
                );

        assertThat(
                beforeReservation.stockQuantity()
        )
                .isEqualTo(10);


        // Prove that this product is really cached.
        jdbcTemplate.update(
                """
                UPDATE products
                SET description = ?
                WHERE id = ?
                """,
                "Changed directly in PostgreSQL",
                productId
        );


        ProductResponse cachedResponse =
                productService.getById(
                        productId
                );

        assertThat(
                cachedResponse.description()
        )
                .as("Value should come from Redis before reservation")
                .isEqualTo(
                        "Original description"
                );


        // Reserve 3 units.
        productService.reserve(
                productId,
                new StockRequest(3),
                reservationKey
        );


        Integer stockInDatabaseAfterReserve =
                jdbcTemplate.queryForObject(
                        """
                        SELECT stock_quantity
                        FROM products
                        WHERE id = ?
                        """,
                        Integer.class,
                        productId
                );

        assertThat(
                stockInDatabaseAfterReserve
        )
                .as("Reservation should update PostgreSQL")
                .isEqualTo(7);


        // Without cache eviction this would still return 10.
        ProductResponse afterReservation =
                productService.getById(
                        productId
                );

        assertThat(
                afterReservation.stockQuantity()
        )
                .as("GET after reservation should return updated stock")
                .isEqualTo(7);


        // Release the same reservation.
        productService.release(
                productId,
                new StockRequest(3),
                reservationKey
        );


        Integer stockInDatabaseAfterRelease =
                jdbcTemplate.queryForObject(
                        """
                        SELECT stock_quantity
                        FROM products
                        WHERE id = ?
                        """,
                        Integer.class,
                        productId
                );

        assertThat(
                stockInDatabaseAfterRelease
        )
                .as("Release should restore stock in PostgreSQL")
                .isEqualTo(10);


        // Without cache eviction this would still return 7.
        ProductResponse afterRelease =
                productService.getById(
                        productId
                );

        assertThat(
                afterRelease.stockQuantity()
        )
                .as("GET after release should return restored stock")
                .isEqualTo(10);
    }


    private Product createProduct(
            String name,
            int stockQuantity
    ) {

        Product product =
                new Product();

        product.setName(
                name
        );

        product.setDescription(
                "Original description"
        );

        product.setPrice(
                new BigDecimal(
                        "1999.99"
                )
        );

        product.setStockQuantity(
                stockQuantity
        );

        product.setActive(
                true
        );

        return product;
    }
}