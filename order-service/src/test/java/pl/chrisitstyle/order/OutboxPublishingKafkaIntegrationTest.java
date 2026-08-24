package pl.chrisitstyle.order;

import io.opentelemetry.api.trace.Span;

import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;

import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;

import pl.chrisitstyle.order.observability.OutboxTraceLinker;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Testcontainers
class OutboxPublishingKafkaIntegrationTest {

    private static final String TOPIC =
            "order.created.v1";

    private static final String WORKER_ID =
            "test-worker";

    @Container
    static final KafkaContainer kafka =
            new KafkaContainer(
                    "apache/kafka-native:3.8.0"
            );

    private DefaultKafkaProducerFactory<String, String>
            producerFactory;

    private KafkaConsumer<String, String> consumer;

    private OutboxStateService outboxStateService;

    private OutboxTraceLinker outboxTraceLinker;

    private OutboxPublishingService publishingService;

    @BeforeEach
    void setUp() throws Exception {

        createTopic();

        Map<String, Object> producerProperties =
                new HashMap<>();

        producerProperties.put(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG,
                kafka.getBootstrapServers()
        );

        producerProperties.put(
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG,
                StringSerializer.class
        );

        producerProperties.put(
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG,
                StringSerializer.class
        );

        producerFactory =
                new DefaultKafkaProducerFactory<>(
                        producerProperties
                );

        KafkaTemplate<String, String> kafkaTemplate =
                new KafkaTemplate<>(
                        producerFactory
                );

        Map<String, Object> consumerProperties =
                new HashMap<>();

        consumerProperties.put(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,
                kafka.getBootstrapServers()
        );

        consumerProperties.put(
                ConsumerConfig.GROUP_ID_CONFIG,
                "outbox-test-" + UUID.randomUUID()
        );

        consumerProperties.put(
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG,
                "earliest"
        );

        consumerProperties.put(
                ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG,
                false
        );

        consumerProperties.put(
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,
                StringDeserializer.class
        );

        consumerProperties.put(
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,
                StringDeserializer.class
        );

        consumer =
                new KafkaConsumer<>(
                        consumerProperties
                );

        consumer.subscribe(
                List.of(TOPIC)
        );

        outboxStateService =
                mock(OutboxStateService.class);

        outboxTraceLinker =
                mock(OutboxTraceLinker.class);

        publishingService =
                new OutboxPublishingService(
                        kafkaTemplate,
                        outboxStateService,
                        outboxTraceLinker
                );
    }

    @AfterEach
    void tearDown() {

        consumer.close();

        producerFactory.destroy();
    }

    @Test
    void shouldPublishOutboxEventToKafkaAndMarkItAsPublished() {

        UUID eventId =
                UUID.randomUUID();

        String payload =
                """
                {
                  "orderId":42,
                  "userId":123,
                  "totalAmount":19.99
                }
                """;

        OutboxClaim claim =
                new OutboxClaim(
                        eventId,
                        "OrderCreated",
                        42L,
                        TOPIC,
                        "42",
                        payload,
                        null,
                        null);


                        when(
                outboxTraceLinker.startLinkedSpan(claim)
        )
                .thenReturn(
                        Span.getInvalid()
                );

        when(
                outboxStateService.markPublished(
                        eventId,
                        WORKER_ID
                )
        )
                .thenReturn(true);

        publishingService.publish(
                claim,
                WORKER_ID
        );

        ConsumerRecord<String, String> record =
                pollForRecord();

        assertThat(record)
                .isNotNull();

        assertThat(record.topic())
                .isEqualTo(TOPIC);

        assertThat(record.key())
                .isEqualTo("42");

        assertThat(record.value())
                .isEqualTo(payload);

        verify(outboxStateService)
                .markPublished(
                        eventId,
                        WORKER_ID
                );

        verify(outboxStateService, never())
                .release(
                        eventId,
                        WORKER_ID
                );
    }

    private void createTopic()
            throws Exception {

        Map<String, Object> adminProperties =
                Map.of(
                        AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG,
                        kafka.getBootstrapServers()
                );

        try (
                AdminClient adminClient =
                        AdminClient.create(
                                adminProperties
                        )
        ) {

            adminClient.createTopics(
                            List.of(
                                    new NewTopic(
                                            TOPIC,
                                            1,
                                            (short) 1
                                    )
                            )
                    )
                    .all()
                    .get();
        }
    }

    private ConsumerRecord<String, String>
    pollForRecord() {

        long deadline =
                System.nanoTime()
                        + Duration.ofSeconds(10)
                        .toNanos();

        while (System.nanoTime() < deadline) {

            ConsumerRecords<String, String> records =
                    consumer.poll(
                            Duration.ofMillis(250)
                    );

            for (
                    ConsumerRecord<String, String> record
                    : records
            ) {

                if (record.topic().equals(TOPIC)) {
                    return record;
                }
            }
        }

        return null;
    }
}