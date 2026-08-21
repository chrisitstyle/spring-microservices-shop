package pl.chrisitstyle.product;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/products")
@RequiredArgsConstructor
public class ProductController {

    private final ProductService productService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ProductResponse create(@Valid @RequestBody CreateProductRequest request) {
        return productService.create(request);
    }

    @PostMapping("/{id}/reserve")
    public ProductReservationResponse reserve(
            @PathVariable Long id,
            @RequestHeader("Idempotency-Key") UUID idempotencyKey,
            @Valid @RequestBody StockRequest request
    ) {
        return productService.reserve(
                id,
                request,
                idempotencyKey);
    }

    @PostMapping("/{id}/release")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void release(
            @PathVariable Long id,
            @RequestHeader("Idempotency-Key") UUID idempotencyKey,
            @Valid @RequestBody StockRequest request
    ) {
        productService.release(
                id,
                request,
                idempotencyKey
        );
    }

    @GetMapping("/{id}")
    public ProductResponse getById(@PathVariable Long id) {
        return productService.getById(id);
    }

    @GetMapping
    public List<ProductResponse> getAll() {
        return productService.getAll();
    }

    @PutMapping("/{id}")
    public ProductResponse update(
            @PathVariable Long id,
            @Valid @RequestBody UpdateProductRequest request
    ) {
        return productService.update(id, request);
    }



    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id) {
        productService.delete(id);
    }

}
