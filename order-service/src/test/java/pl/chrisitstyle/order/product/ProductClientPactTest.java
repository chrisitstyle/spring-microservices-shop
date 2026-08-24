package pl.chrisitstyle.order.product;

import au.com.dius.pact.consumer.MockServer;
import au.com.dius.pact.consumer.dsl.PactDslJsonBody;
import au.com.dius.pact.consumer.dsl.PactDslWithProvider;
import au.com.dius.pact.consumer.junit5.PactConsumerTestExt;
import au.com.dius.pact.consumer.junit5.PactTestFor;
import au.com.dius.pact.core.model.PactSpecVersion;
import au.com.dius.pact.core.model.V4Pact;
import au.com.dius.pact.core.model.annotations.Pact;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.security.oauth2.client.web.client.OAuth2ClientHttpRequestInterceptor;
import org.springframework.web.client.RestClient;

import pl.chrisitstyle.order.ProductClient;
import pl.chrisitstyle.order.ProductReservationResponse;
import pl.chrisitstyle.order.exception.OrderCreationException;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(PactConsumerTestExt.class)
class ProductClientPactTest {

    private static final Long PRODUCT_ID = 42L;
    private static final Integer QUANTITY = 2;

    private static final UUID RESERVATION_KEY =
            UUID.fromString(
                    "123e4567-e89b-12d3-a456-426614174000"
            );

    private static final String UUID_REGEX =
            "[0-9a-fA-F]{8}-"
                    + "[0-9a-fA-F]{4}-"
                    + "[0-9a-fA-F]{4}-"
                    + "[0-9a-fA-F]{4}-"
                    + "[0-9a-fA-F]{12}";

    @Pact(
            consumer = "order-service",
            provider = "product-service"
    )
    V4Pact reserveStock(PactDslWithProvider builder) {

        PactDslJsonBody requestBody =
                new PactDslJsonBody()
                        .integerType(
                                "quantity",
                                QUANTITY
                        );

        PactDslJsonBody responseBody =
                new PactDslJsonBody()
                        .integerType(
                                "productId",
                                PRODUCT_ID
                        )
                        .integerType(
                                "quantity",
                                QUANTITY
                        )
                        .decimalType(
                                "unitPrice",
                                19.99
                        );

        return builder
                .given(
                        "product 42 has sufficient stock"
                )
                .uponReceiving(
                        "a request to reserve product stock"
                )
                .path(
                        "/products/42/reserve"
                )
                .method(
                        "POST"
                )
                .matchHeader(
                        "Idempotency-Key",
                        UUID_REGEX,
                        RESERVATION_KEY.toString()
                )
                .matchHeader(
                        "Content-Type",
                        "application/json.*",
                        "application/json"
                )
                .body(
                        requestBody
                )
                .willRespondWith()
                .status(
                        200
                )
                .matchHeader(
                        "Content-Type",
                        "application/json.*",
                        "application/json"
                )
                .body(
                        responseBody
                )
                .toPact(
                        V4Pact.class
                );
    }

    @Pact(
            consumer = "order-service",
            provider = "product-service"
    )
    V4Pact reserveStockWithInsufficientStock(
            PactDslWithProvider builder
    ) {

        PactDslJsonBody requestBody =
                new PactDslJsonBody()
                        .integerType(
                                "quantity",
                                QUANTITY
                        );

        return builder
                .given(
                        "product 42 has insufficient stock"
                )
                .uponReceiving(
                        "a request to reserve product stock when stock is insufficient"
                )
                .path(
                        "/products/42/reserve"
                )
                .method(
                        "POST"
                )
                .matchHeader(
                        "Idempotency-Key",
                        UUID_REGEX,
                        RESERVATION_KEY.toString()
                )
                .matchHeader(
                        "Content-Type",
                        "application/json.*",
                        "application/json"
                )
                .body(
                        requestBody
                )
                .willRespondWith()
                .status(
                        409
                )
                .toPact(
                        V4Pact.class
                );
    }

    @Pact(
            consumer = "order-service",
            provider = "product-service"
    )
    V4Pact releaseStock(PactDslWithProvider builder) {

        PactDslJsonBody requestBody =
                new PactDslJsonBody()
                        .integerType(
                                "quantity",
                                QUANTITY
                        );

        return builder
                .given(
                        "product 42 has an active reservation"
                )
                .uponReceiving(
                        "a request to release product stock"
                )
                .path(
                        "/products/42/release"
                )
                .method(
                        "POST"
                )
                .matchHeader(
                        "Idempotency-Key",
                        UUID_REGEX,
                        RESERVATION_KEY.toString()
                )
                .matchHeader(
                        "Content-Type",
                        "application/json.*",
                        "application/json"
                )
                .body(
                        requestBody
                )
                .willRespondWith()
                .status(
                        204
                )
                .toPact(
                        V4Pact.class
                );
    }

    @Test
    @PactTestFor(
            pactMethod = "reserveStock",
            pactVersion = PactSpecVersion.V4
    )
    void shouldReserveStock(
            MockServer mockServer
    ) throws IOException {

        ProductClient productClient =
                createProductClient(
                        mockServer
                );

        ProductReservationResponse response =
                productClient.reserve(
                        PRODUCT_ID,
                        QUANTITY,
                        RESERVATION_KEY
                );

        assertThat(response)
                .isNotNull();

        assertThat(response.productId())
                .isEqualTo(PRODUCT_ID);

        assertThat(response.quantity())
                .isEqualTo(QUANTITY);

        assertThat(response.unitPrice())
                .isEqualByComparingTo(
                        new BigDecimal("19.99")
                );
    }

    @Test
    @PactTestFor(
            pactMethod = "reserveStockWithInsufficientStock",
            pactVersion = PactSpecVersion.V4
    )
    void shouldRejectReservationWhenStockIsInsufficient(
            MockServer mockServer
    ) throws IOException {

        ProductClient productClient =
                createProductClient(
                        mockServer
                );

        assertThatThrownBy(
                () -> productClient.reserve(
                        PRODUCT_ID,
                        QUANTITY,
                        RESERVATION_KEY
                )
        )
                .isInstanceOf(
                        OrderCreationException.class
                )
                .hasMessage(
                        "Cannot reserve product 42"
                );
    }

    @Test
    @PactTestFor(
            pactMethod = "releaseStock",
            pactVersion = PactSpecVersion.V4
    )
    void shouldReleaseStock(
            MockServer mockServer
    ) throws IOException {

        ProductClient productClient =
                createProductClient(
                        mockServer
                );

        assertThatCode(
                () -> productClient.release(
                        PRODUCT_ID,
                        QUANTITY,
                        RESERVATION_KEY
                )
        ).doesNotThrowAnyException();
    }

    private ProductClient createProductClient(
            MockServer mockServer
    ) throws IOException {

        OAuth2ClientHttpRequestInterceptor oauth2Interceptor =
                createNoOpOAuth2Interceptor();

        return new ProductClient(
                RestClient.builder(),
                oauth2Interceptor,
                mockServer.getUrl()
        );
    }

    private OAuth2ClientHttpRequestInterceptor
    createNoOpOAuth2Interceptor() throws IOException {

        OAuth2ClientHttpRequestInterceptor interceptor =
                mock(
                        OAuth2ClientHttpRequestInterceptor.class
                );

        when(
                interceptor.intercept(
                        any(),
                        any(),
                        any()
                )
        ).thenAnswer(invocation -> {

            HttpRequest request =
                    invocation.getArgument(0);

            byte[] body =
                    invocation.getArgument(1);

            ClientHttpRequestExecution execution =
                    invocation.getArgument(2);

            return execution.execute(
                    request,
                    body
            );
        });

        return interceptor;
    }
}