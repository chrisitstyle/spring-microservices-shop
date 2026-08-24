package pl.chrisitstyle.notification;

import org.apache.kafka.clients.admin.NewTopic;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

@Testcontainers
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE
)
@Import(
        OrderCreatedKafkaIntegrationTest.KafkaTopicConfiguration.class
)
class OrderCreatedKafkaIntegrationTest {

    private static final String TOPIC =
            "order.created.v1";

    private static final Long ORDER_ID =
            42L;

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>(
                    "postgres:17"
            );

    @Container
    @ServiceConnection
    static final KafkaContainer kafka =
            new KafkaContainer(
                    "apache/kafka-native:3.8.0"
            );

    @Autowired
    private KafkaTemplate<Object, Object> kafkaTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private NotificationMetrics notificationMetrics;

    @BeforeEach
    void cleanDatabase() {

        jdbcTemplate.update(
                "DELETE FROM processed_order_events"
        );
    }

    @Test
    void shouldProcessDuplicateOrderCreatedEventOnlyOnce()
            throws Exception {

        OrderCreatedEvent event =
                new OrderCreatedEvent(
                        ORDER_ID,
                        123L,
                        new BigDecimal("19.99"),
                        OffsetDateTime.now()
                );

        kafkaTemplate.send(
                        TOPIC,
                        ORDER_ID.toString(),
                        event
                )
                .get(
                        10,
                        TimeUnit.SECONDS
                );

        kafkaTemplate.send(
                        TOPIC,
                        ORDER_ID.toString(),
                        event
                )
                .get(
                        10,
                        TimeUnit.SECONDS
                );

        verify(
                notificationMetrics,
                timeout(10_000).times(1)
        )
                .incrementProcessed();

        verify(
                notificationMetrics,
                timeout(10_000).times(1)
        )
                .incrementDuplicates();

        Integer processedEventCount =
                jdbcTemplate.queryForObject(
                        """
                        SELECT COUNT(*)
                        FROM processed_order_events
                        WHERE order_id = ?
                        """,
                        Integer.class,
                        ORDER_ID
                );

        assertThat(processedEventCount)
                .isEqualTo(1);

        verify(
                notificationMetrics,
                never()
        )
                .incrementDlt();
    }

    @TestConfiguration(
            proxyBeanMethods = false
    )
    static class KafkaTopicConfiguration {

        @Bean
        NewTopic orderCreatedTopic() {

            return TopicBuilder
                    .name(TOPIC)
                    .partitions(1)
                    .replicas(1)
                    .build();
        }
    }
}