package pl.chrisitstyle.order;

import feign.FeignException;
import feign.RetryableException;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import org.springframework.stereotype.Component;
import pl.chrisitstyle.order.exception.ExternalServiceException;
import pl.chrisitstyle.order.exception.OrderCreationException;

import java.util.UUID;

@Component
public class ProductClient {

    private final ProductFeignClient productFeignClient;

    public ProductClient(
            ProductFeignClient productFeignClient
    ) {
        this.productFeignClient = productFeignClient;
    }

    @Retry(name = "productService")
    @CircuitBreaker(name = "productService")
    public ProductReservationResponse reserve(
            Long productId,
            Integer quantity,
            UUID idempotencyKey
    ) {
        try {
            return productFeignClient.reserve(
                    productId,
                    idempotencyKey.toString(),
                    new StockRequest(quantity)
            );

        } catch (RetryableException exception) {
            throw new ExternalServiceException(
                    "Product service unavailable",
                    exception
            );

        } catch (FeignException exception) {

            if (exception.status() == 404) {
                throw new OrderCreationException(
                        "Product " + productId + " not found"
                );
            }

            if (exception.status() == 409) {
                throw new OrderCreationException(
                        "Cannot reserve product " + productId
                );
            }

            throw new ExternalServiceException(
                    "Product service returned error: "
                            + exception.status(),
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
            productFeignClient.release(
                    productId,
                    reservationKey.toString(),
                    new StockRequest(quantity)
            );

        } catch (RetryableException exception) {
            throw new ExternalServiceException(
                    "Product service unavailable while releasing stock",
                    exception
            );

        } catch (FeignException exception) {

            if (exception.status() == 404) {
                throw new OrderCreationException(
                        "Cannot release stock because product "
                                + productId + " was not found"
                );
            }

            if (exception.status() == 409) {
                throw new OrderCreationException(
                        "Cannot release stock reservation for product "
                                + productId
                );
            }

            throw new ExternalServiceException(
                    "Product service returned error while releasing stock: "
                            + exception.status(),
                    exception
            );
        }
    }
}