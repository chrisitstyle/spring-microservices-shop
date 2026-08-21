package pl.chrisitstyle.order;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import pl.chrisitstyle.order.exception.ExternalServiceException;
import pl.chrisitstyle.order.exception.OrderCreationException;

import java.util.UUID;

@Component
public class ProductClient {

    private final RestClient restClient;

    public ProductClient(
            RestClient.Builder builder,
            @Value("${services.product.url}") String productServiceUrl
    ) {
        this.restClient = builder
                .baseUrl(productServiceUrl)
                .build();
    }

    @Retry(name = "productService")
    @CircuitBreaker(name = "productService")
    public ProductReservationResponse reserve(
            Long productId,
            Integer quantity,
            UUID idempotencyKey
    ) {
        try {
            return restClient.post()
                    .uri("/products/{id}/reserve", productId)
                    .header(
                            "Idempotency-Key",
                            idempotencyKey.toString()
                    )
                    .body(new StockRequest(quantity))
                    .retrieve()
                    .body(ProductReservationResponse.class);

        } catch (RestClientResponseException exception) {

            if (exception.getStatusCode() == HttpStatus.NOT_FOUND) {
                throw new OrderCreationException(
                        "Product " + productId + " not found"
                );
            }

            if (exception.getStatusCode() == HttpStatus.CONFLICT) {
                throw new OrderCreationException(
                        "Cannot reserve product " + productId
                );
            }

            throw new ExternalServiceException(
                    "Product service returned error: "
                            + exception.getStatusCode(),
                    exception
            );

        } catch (ResourceAccessException exception) {
            throw new ExternalServiceException(
                    "Product service unavailable",
                    exception
            );

        } catch (RestClientException exception) {
            throw new ExternalServiceException(
                    "Product service communication failed",
                    exception
            );
        }
    }

    @Retry(name = "productServiceRelease")
    public void release(
            Long productId,
            Integer quantity,
            UUID reservationKey
    ) {
        try {
            restClient.post()
                    .uri("/products/{id}/release", productId)
                    .header(
                            "Idempotency-Key",
                            reservationKey.toString()
                    )
                    .body(new StockRequest(quantity))
                    .retrieve()
                    .toBodilessEntity();

        } catch (RestClientResponseException exception) {

            if (exception.getStatusCode() == HttpStatus.NOT_FOUND) {
                throw new OrderCreationException(
                        "Cannot release stock because product "
                                + productId + " was not found"
                );
            }

            if (exception.getStatusCode() == HttpStatus.CONFLICT) {
                throw new OrderCreationException(
                        "Cannot release stock reservation for product "
                                + productId
                );
            }

            throw new ExternalServiceException(
                    "Product service returned error while releasing stock: "
                            + exception.getStatusCode(),
                    exception
            );

        } catch (ResourceAccessException exception) {
            throw new ExternalServiceException(
                    "Product service unavailable while releasing stock",
                    exception
            );

        } catch (RestClientException exception) {
            throw new ExternalServiceException(
                    "Product service communication failed while releasing stock",
                    exception
            );
        }
    }
}