package pl.chrisitstyle.order;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import pl.chrisitstyle.order.exception.ExternalServiceException;
import pl.chrisitstyle.order.exception.OrderCreationException;

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

    @CircuitBreaker(name = "productService")
    public ProductReservationResponse reserve(
            Long productId,
            Integer quantity
    ) {
        try {
            return restClient.post()
                    .uri("/products/{id}/reserve", productId)
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

    public void release(
            Long productId,
            Integer quantity
    ) {
        try {
            restClient.post()
                    .uri("/products/{id}/release", productId)
                    .body(new StockRequest(quantity))
                    .retrieve()
                    .toBodilessEntity();

        } catch (RestClientException exception) {
            throw new ExternalServiceException(
                    "Could not release stock for product " + productId,
                    exception
            );
        }
    }
}