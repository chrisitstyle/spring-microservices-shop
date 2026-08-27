package pl.chrisitstyle.order;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;

@FeignClient(name = "product-service")
public interface ProductFeignClient {

    @PostMapping(
            value = "/products/{id}/reserve",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    ProductReservationResponse reserve(
            @PathVariable("id") Long productId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody StockRequest request
    );

    @PostMapping(
            value = "/products/{id}/release",
            consumes = MediaType.APPLICATION_JSON_VALUE
    )
    void release(
            @PathVariable("id") Long productId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody StockRequest request
    );
}