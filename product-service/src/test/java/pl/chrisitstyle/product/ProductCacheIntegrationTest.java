package pl.chrisitstyle.product;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

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
    static final GenericContainer<?> redis =
            new GenericContainer<>(
                    "redis:8-alpine"
            )
                    .withExposedPorts(
                            6379
                    );

    @DynamicPropertySource
    static void redisProperties(
            DynamicPropertyRegistry registry
    ) {

        registry.add(
                "spring.data.redis.host",
                redis::getHost
        );

        registry.add(
                "spring.data.redis.port",
                () -> redis.getMappedPort(
                        6379
                )
        );
    }


    @Autowired
    private ProductService productService;

    @Autowired
    private ProductRepository productRepository;


    @Autowired
    private CacheManager cacheManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;


    @BeforeEach
    void cleanState() {

        jdbcTemplate.update(
                "DELETE FROM stock_reservation_requests"
        );

        productRepository.deleteAll();

        Cache productsCache =
                cacheManager.getCache(
                        "products"
                );

        if (productsCache != null) {
            productsCache.clear();
        }
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


        ProductResponse firstResponse =
                productService.getById(
                        productId
                );


        jdbcTemplate.update(
                """
                UPDATE products
                SET description = ?
                WHERE id = ?
                """,
                "Changed directly in PostgreSQL",
                productId
        );


        ProductResponse secondResponse =
                productService.getById(
                        productId
                );


        assertThat(
                firstResponse.description()
        )
                .isEqualTo(
                        "Original description"
                );

        assertThat(
                secondResponse.description()
        )
                .isEqualTo(
                        "Original description"
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


        ProductResponse cachedResponse =
                productService.getById(
                        productId
                );

        assertThat(
                cachedResponse.description()
        )
                .isEqualTo(
                        "Original description"
                );


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


        ProductResponse responseAfterUpdate =
                productService.getById(
                        productId
                );


        assertThat(
                responseAfterUpdate.description()
        )
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

        Cache productsCache =
                cacheManager.getCache(
                        "products"
                );

        assertThat(productsCache)
                .isNotNull();


        // ========================================================
        // POPULATE CACHE
        // ========================================================

        ProductResponse beforeReservation =
                productService.getById(
                        productId
                );

        assertThat(
                beforeReservation.stockQuantity()
        )
                .isEqualTo(10);

        assertThat(
                productsCache.get(productId)
        )
                .as("Product should be cached before reservation")
                .isNotNull();


        // ========================================================
        // RESERVE
        // ========================================================

        productService.reserve(
                productId,
                new StockRequest(3),
                reservationKey
        );


        // First verify PostgreSQL itself.
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
                .as("Stock should be updated in PostgreSQL")
                .isEqualTo(7);


        // Then verify cache eviction independently.
        assertThat(
                productsCache.get(productId)
        )
                .as("Cache should be evicted after reserve")
                .isNull();


        ProductResponse afterReservation =
                productService.getById(
                        productId
                );

        assertThat(
                afterReservation.stockQuantity()
        )
                .isEqualTo(7);


        // GET should populate cache again.
        assertThat(
                productsCache.get(productId)
        )
                .as("Product should be cached again after GET")
                .isNotNull();


        // ========================================================
        // RELEASE
        // ========================================================

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
                .as("Stock should be restored in PostgreSQL")
                .isEqualTo(10);


        assertThat(
                productsCache.get(productId)
        )
                .as("Cache should be evicted after release")
                .isNull();


        ProductResponse afterRelease =
                productService.getById(
                        productId
                );

        assertThat(
                afterRelease.stockQuantity()
        )
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