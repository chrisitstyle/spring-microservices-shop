package pl.chrisitstyle.e2e;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import pl.chrisitstyle.e2e.support.DatabaseE2ESupport;
import pl.chrisitstyle.e2e.support.KeycloakE2ESupport;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class OrderCreationE2ETest {

    private static final String GATEWAY_BASE_URL =
            System.getProperty(
                    "e2e.gateway.base-url",
                    "http://localhost:8085"
            );

    private static final String ADMIN_USERNAME =
            "e2e-admin";

    private static final String ADMIN_PASSWORD =
            "e2e-password";

    private static final String CUSTOMER_USERNAME =
            "e2e-customer";

    private static final String CUSTOMER_PASSWORD =
            "e2e-password";

    private static final String CUSTOMER_EMAIL =
            "e2e-customer@example.test";

    private static final KeycloakE2ESupport keycloak =
            new KeycloakE2ESupport();

    private static final DatabaseE2ESupport database =
            new DatabaseE2ESupport();

    private static Long shopUserId;

    private final HttpClient httpClient =
            HttpClient.newBuilder()
                    .connectTimeout(
                            Duration.ofSeconds(5)
                    )
                    .build();

    private final ObjectMapper objectMapper =
            new ObjectMapper();

    @BeforeAll
    static void prepareIdentityAndShopUser()
            throws Exception {

        keycloak.ensureTestClient();

        keycloak.ensureRealmUser(
                ADMIN_USERNAME,
                ADMIN_PASSWORD,
                "ADMIN"
        );

        keycloak.ensureRealmUser(
                CUSTOMER_USERNAME,
                CUSTOMER_PASSWORD,
                "CUSTOMER"
        );

        String keycloakSubject =
                keycloak.getRealmUserId(
                        CUSTOMER_USERNAME
                );

        shopUserId =
                database.ensureShopUser(
                        CUSTOMER_USERNAME,
                        CUSTOMER_EMAIL,
                        keycloakSubject
                );
    }

    @Test
    void shouldCreateOrderAndCompleteEntireOrderCreatedFlow()
            throws Exception {

        String adminToken =
                keycloak.getUserAccessToken(
                        ADMIN_USERNAME,
                        ADMIN_PASSWORD
                );

        String customerToken =
                keycloak.getUserAccessToken(
                        CUSTOMER_USERNAME,
                        CUSTOMER_PASSWORD
                );

        Long productId =
                createProduct(
                        adminToken,
                        "E2E Laptop",
                        new BigDecimal("19.99"),
                        10
                );

        JsonNode createdOrder =
                createOrder(
                        customerToken,
                        productId,
                        2
                );

        Long orderId =
                createdOrder
                        .path("id")
                        .asLong();

        assertThat(orderId)
                .isPositive();

        assertThat(
                createdOrder
                        .path("userId")
                        .asLong()
        )
                .isEqualTo(
                        shopUserId
                );

        assertThat(
                createdOrder
                        .path("status")
                        .asText()
        )
                .isEqualTo(
                        "CREATED"
                );

        assertThat(
                createdOrder
                        .path("totalAmount")
                        .decimalValue()
        )
                .isEqualByComparingTo(
                        new BigDecimal("39.98")
                );

        JsonNode items =
                createdOrder.path(
                        "items"
                );

        assertThat(items)
                .hasSize(1);

        assertThat(
                items.get(0)
                        .path("productId")
                        .asLong()
        )
                .isEqualTo(
                        productId
                );

        assertThat(
                items.get(0)
                        .path("quantity")
                        .asInt()
        )
                .isEqualTo(2);

        assertThat(
                items.get(0)
                        .path("unitPrice")
                        .decimalValue()
        )
                .isEqualByComparingTo(
                        new BigDecimal("19.99")
                );

        JsonNode productAfterOrder =
                getProduct(
                        productId
                );

        assertThat(
                productAfterOrder
                        .path("stockQuantity")
                        .asInt()
        )
                .isEqualTo(8);

        await()
                .alias(
                        "Saga should reach COMPLETED state"
                )
                .atMost(
                        Duration.ofSeconds(10)
                )
                .pollInterval(
                        Duration.ofMillis(250)
                )
                .untilAsserted(
                        () ->
                                assertThat(
                                        database.findSagaStatusByOrderId(
                                                orderId
                                        )
                                )
                                        .isEqualTo(
                                                "COMPLETED"
                                        )
                );

        await()
                .alias(
                        "OrderCreated outbox event should be published"
                )
                .atMost(
                        Duration.ofSeconds(15)
                )
                .pollInterval(
                        Duration.ofMillis(250)
                )
                .untilAsserted(
                        () ->
                                assertThat(
                                        database.isOrderCreatedEventPublished(
                                                orderId
                                        )
                                )
                                        .isTrue()
                );

        await()
                .alias(
                        "Notification service should process OrderCreated event"
                )
                .atMost(
                        Duration.ofSeconds(15)
                )
                .pollInterval(
                        Duration.ofMillis(250)
                )
                .untilAsserted(
                        () ->
                                assertThat(
                                        database.isNotificationProcessed(
                                                orderId
                                        )
                                )
                                        .isTrue()
                );
    }

    @Test
    void shouldRejectOrderWhenProductHasInsufficientStock()
            throws Exception {

        String adminToken =
                keycloak.getUserAccessToken(
                        ADMIN_USERNAME,
                        ADMIN_PASSWORD
                );

        String customerToken =
                keycloak.getUserAccessToken(
                        CUSTOMER_USERNAME,
                        CUSTOMER_PASSWORD
                );

        Long productId =
                createProduct(
                        adminToken,
                        "E2E Low Stock Product",
                        new BigDecimal("9.99"),
                        1
                );

        long ordersBefore =
                database.countOrdersByUserId(
                        shopUserId
                );

        long outboxEventsBefore =
                database.countOrderCreatedEvents();

        HttpResponse<String> response =
                sendCreateOrder(
                        customerToken,
                        List.of(
                                orderItem(
                                        productId,
                                        5
                                )
                        )
                );

        assertThat(response.statusCode())
                .isEqualTo(409);

        JsonNode error =
                objectMapper.readTree(
                        response.body()
                );

        assertThat(
                error.path("message")
                        .asText()
        )
                .isEqualTo(
                        "Cannot reserve product "
                                + productId
                );

        JsonNode productAfterFailure =
                getProduct(
                        productId
                );

        assertThat(
                productAfterFailure
                        .path("stockQuantity")
                        .asInt()
        )
                .isEqualTo(1);

        await()
                .alias(
                        "Failed order Saga should be compensated"
                )
                .atMost(
                        Duration.ofSeconds(10)
                )
                .pollInterval(
                        Duration.ofMillis(250)
                )
                .untilAsserted(
                        () ->
                                assertThat(
                                        database.findLatestSagaStatusByUserId(
                                                shopUserId
                                        )
                                )
                                        .isEqualTo(
                                                "COMPENSATED"
                                        )
                );

        assertThat(
                database.countOrdersByUserId(
                        shopUserId
                )
        )
                .isEqualTo(
                        ordersBefore
                );

        assertThat(
                database.countOrderCreatedEvents()
        )
                .isEqualTo(
                        outboxEventsBefore
                );
    }

    @Test
    void shouldCompensatePreviouslyReservedProductWhenLaterReservationFails()
            throws Exception {

        String adminToken =
                keycloak.getUserAccessToken(
                        ADMIN_USERNAME,
                        ADMIN_PASSWORD
                );

        String customerToken =
                keycloak.getUserAccessToken(
                        CUSTOMER_USERNAME,
                        CUSTOMER_PASSWORD
                );

        Long reservableProductId =
                createProduct(
                        adminToken,
                        "E2E Compensated Product",
                        new BigDecimal("19.99"),
                        10
                );

        Long insufficientProductId =
                createProduct(
                        adminToken,
                        "E2E Insufficient Product",
                        new BigDecimal("29.99"),
                        1
                );

        long ordersBefore =
                database.countOrdersByUserId(
                        shopUserId
                );

        long outboxEventsBefore =
                database.countOrderCreatedEvents();

        HttpResponse<String> response =
                sendCreateOrder(
                        customerToken,
                        List.of(
                                orderItem(
                                        reservableProductId,
                                        3
                                ),
                                orderItem(
                                        insufficientProductId,
                                        5
                                )
                        )
                );

        assertThat(response.statusCode())
                .isEqualTo(409);

        JsonNode error =
                objectMapper.readTree(
                        response.body()
                );

        assertThat(
                error.path("message")
                        .asText()
        )
                .isEqualTo(
                        "Cannot reserve product "
                                + insufficientProductId
                );

        await()
                .alias(
                        "Multi-item Saga should compensate successful reservations"
                )
                .atMost(
                        Duration.ofSeconds(10)
                )
                .pollInterval(
                        Duration.ofMillis(250)
                )
                .untilAsserted(
                        () ->
                                assertThat(
                                        database.findLatestSagaStatusByUserId(
                                                shopUserId
                                        )
                                )
                                        .isEqualTo(
                                                "COMPENSATED"
                                        )
                );

        JsonNode firstProductAfterCompensation =
                getProduct(
                        reservableProductId
                );

        JsonNode secondProductAfterFailure =
                getProduct(
                        insufficientProductId
                );

        assertThat(
                firstProductAfterCompensation
                        .path("stockQuantity")
                        .asInt()
        )
                .isEqualTo(10);

        assertThat(
                secondProductAfterFailure
                        .path("stockQuantity")
                        .asInt()
        )
                .isEqualTo(1);

        assertThat(
                database.countOrdersByUserId(
                        shopUserId
                )
        )
                .isEqualTo(
                        ordersBefore
                );

        assertThat(
                database.countOrderCreatedEvents()
        )
                .isEqualTo(
                        outboxEventsBefore
                );
    }

    private Long createProduct(
            String adminToken,
            String name,
            BigDecimal price,
            int stockQuantity
    ) throws Exception {

        Map<String, Object> body =
                new LinkedHashMap<>();

        body.put(
                "name",
                name
        );

        body.put(
                "description",
                "Product created by E2E test"
        );

        body.put(
                "price",
                price
        );

        body.put(
                "stockQuantity",
                stockQuantity
        );

        HttpRequest request =
                HttpRequest.newBuilder()
                        .uri(
                                URI.create(
                                        GATEWAY_BASE_URL
                                                + "/products"
                                )
                        )
                        .timeout(
                                Duration.ofSeconds(10)
                        )
                        .header(
                                "Authorization",
                                "Bearer " + adminToken
                        )
                        .header(
                                "Content-Type",
                                "application/json"
                        )
                        .POST(
                                HttpRequest.BodyPublishers.ofString(
                                        objectMapper.writeValueAsString(
                                                body
                                        )
                                )
                        )
                        .build();

        HttpResponse<String> response =
                httpClient.send(
                        request,
                        HttpResponse.BodyHandlers.ofString()
                );

        assertThat(response.statusCode())
                .withFailMessage(
                        """
                        Product creation failed.

                        HTTP status: %s
                        Body: %s
                        """,
                        response.statusCode(),
                        response.body()
                )
                .isEqualTo(201);

        JsonNode product =
                objectMapper.readTree(
                        response.body()
                );

        return product
                .path("id")
                .asLong();
    }

    private JsonNode createOrder(
            String customerToken,
            Long productId,
            int quantity
    ) throws Exception {

        HttpResponse<String> response =
                sendCreateOrder(
                        customerToken,
                        List.of(
                                orderItem(
                                        productId,
                                        quantity
                                )
                        )
                );

        assertThat(response.statusCode())
                .withFailMessage(
                        """
                        Order creation failed.

                        HTTP status: %s
                        Body: %s
                        """,
                        response.statusCode(),
                        response.body()
                )
                .isEqualTo(201);

        return objectMapper.readTree(
                response.body()
        );
    }

    private HttpResponse<String> sendCreateOrder(
            String customerToken,
            List<Map<String, Object>> items
    ) throws Exception {

        Map<String, Object> body =
                Map.of(
                        "items",
                        items
                );

        HttpRequest request =
                HttpRequest.newBuilder()
                        .uri(
                                URI.create(
                                        GATEWAY_BASE_URL
                                                + "/orders"
                                )
                        )
                        .timeout(
                                Duration.ofSeconds(20)
                        )
                        .header(
                                "Authorization",
                                "Bearer " + customerToken
                        )
                        .header(
                                "Content-Type",
                                "application/json"
                        )
                        .POST(
                                HttpRequest.BodyPublishers.ofString(
                                        objectMapper.writeValueAsString(
                                                body
                                        )
                                )
                        )
                        .build();

        return httpClient.send(
                request,
                HttpResponse.BodyHandlers.ofString()
        );
    }

    private JsonNode getProduct(
            Long productId
    ) throws Exception {

        HttpRequest request =
                HttpRequest.newBuilder()
                        .uri(
                                URI.create(
                                        GATEWAY_BASE_URL
                                                + "/products/"
                                                + productId
                                )
                        )
                        .timeout(
                                Duration.ofSeconds(10)
                        )
                        .GET()
                        .build();

        HttpResponse<String> response =
                httpClient.send(
                        request,
                        HttpResponse.BodyHandlers.ofString()
                );

        assertThat(response.statusCode())
                .withFailMessage(
                        """
                        Product retrieval failed.

                        HTTP status: %s
                        Body: %s
                        """,
                        response.statusCode(),
                        response.body()
                )
                .isEqualTo(200);

        return objectMapper.readTree(
                response.body()
        );
    }

    private Map<String, Object> orderItem(
            Long productId,
            int quantity
    ) {

        return Map.of(
                "productId",
                productId,
                "quantity",
                quantity
        );
    }
}