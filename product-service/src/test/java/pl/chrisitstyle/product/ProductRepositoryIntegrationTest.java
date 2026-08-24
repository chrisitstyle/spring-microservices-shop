package pl.chrisitstyle.product;

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;

import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import pl.chrisitstyle.product.domain.Product;

import jakarta.persistence.EntityManager;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@DataJpaTest(
        properties = {
                "spring.jpa.hibernate.ddl-auto=validate"
        }
)
@AutoConfigureTestDatabase(
        replace = AutoConfigureTestDatabase.Replace.NONE
)
class ProductRepositoryIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:17");

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void shouldUsePostgreSql17AndApplyFlywayMigrations() {

        String postgresVersion =
                jdbcTemplate.queryForObject(
                        "select current_setting('server_version')",
                        String.class
                );

        Integer flywayMigrationCount =
                jdbcTemplate.queryForObject(
                        """
                        select count(*)
                        from flyway_schema_history
                        where success = true
                        """,
                        Integer.class
                );

        assertThat(postgresVersion)
                .startsWith("17.");

        assertThat(flywayMigrationCount)
                .isPositive();
    }

    @Test
    void shouldPersistAndReadProduct() {

        Product product = new Product();
        product.setName("Test Laptop");
        product.setDescription("Product created by integration test");
        product.setPrice(
                new BigDecimal("1999.99")
        );
        product.setStockQuantity(10);
        product.setActive(true);

        Product savedProduct =
                productRepository.saveAndFlush(
                        product
                );

        Long productId =
                savedProduct.getId();

        entityManager.clear();

        Product loadedProduct =
                productRepository.findById(
                                productId
                        )
                        .orElseThrow();

        assertThat(loadedProduct.getId())
                .isEqualTo(productId);

        assertThat(loadedProduct.getName())
                .isEqualTo("Test Laptop");

        assertThat(loadedProduct.getDescription())
                .isEqualTo(
                        "Product created by integration test"
                );

        assertThat(loadedProduct.getPrice())
                .isEqualByComparingTo(
                        new BigDecimal("1999.99")
                );

        assertThat(loadedProduct.getStockQuantity())
                .isEqualTo(10);

        assertThat(loadedProduct.getActive())
                .isTrue();

        assertThat(loadedProduct.getCreatedAt())
                .isNotNull();
    }
}