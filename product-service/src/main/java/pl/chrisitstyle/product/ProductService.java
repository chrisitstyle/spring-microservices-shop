package pl.chrisitstyle.product;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pl.chrisitstyle.product.domain.Product;
import pl.chrisitstyle.product.exception.IdempotencyConflictException;
import pl.chrisitstyle.product.exception.ProductNotFoundException;
import pl.chrisitstyle.product.exception.ProductUnavailableException;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ProductService {

    private final ProductRepository productRepository;
    private final StockReservationRequestRepository stockReservationRequestRepository;

    @Transactional
    public ProductResponse create(CreateProductRequest request) {
        Product product = new Product();

        product.setName(request.name());
        product.setDescription(request.description());
        product.setPrice(request.price());
        product.setStockQuantity(request.stockQuantity());
        product.setActive(true);

        Product savedProduct = productRepository.save(product);

        return toResponse(savedProduct);
    }

    public ProductResponse getById(Long id){
        Product product = productRepository.findById(id)
                .orElseThrow(() -> new ProductNotFoundException("Product not found " + id));

        return toResponse(product);
    }

    @Transactional(readOnly = true)
    public List<ProductResponse> getAll() {
        return productRepository.findAllByActiveTrue()
                .stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional
    public ProductResponse update(Long id, UpdateProductRequest request) {
        Product product = productRepository.findById(id)
                .orElseThrow(() -> new ProductNotFoundException("Product not found " + id));

        product.setName(request.name());
        product.setDescription(request.description());
        product.setPrice(request.price());
        product.setStockQuantity(request.stockQuantity());
        product.setActive(request.active());

        return toResponse(product);
    }

    @Transactional
    public void delete(Long id) {
        Product product = productRepository.findById(id)
                .orElseThrow(() -> new ProductNotFoundException("Product not found " + id));

        product.setActive(false);

    }

    @Transactional
    public ProductReservationResponse reserve(
            Long id,
            StockRequest request,
            UUID idempotencyKey
    ) {
        boolean firstRequest =
                stockReservationRequestRepository.tryRegister(
                        idempotencyKey,
                        id,
                        request.quantity()
                );

        if (!firstRequest) {
            return getPreviousReservation(
                    idempotencyKey,
                    id,
                    request.quantity()
            );
        }

        Product product = productRepository.findById(id)
                .orElseThrow(() ->
                        new ProductNotFoundException(
                                "Product not found " + id
                        )
                );

        if (!product.getActive()) {
            throw new ProductUnavailableException(
                    "Product " + id + " is inactive"
            );
        }

        if (product.getStockQuantity() < request.quantity()) {
            throw new ProductUnavailableException(
                    "Insufficient stock for product " + id
            );
        }

        product.setStockQuantity(
                product.getStockQuantity() - request.quantity()
        );

        ProductReservationResponse response =
                new ProductReservationResponse(
                        product.getId(),
                        request.quantity(),
                        product.getPrice()
                );

        stockReservationRequestRepository.saveResult(
                idempotencyKey,
                product.getPrice()
        );

        return response;
    }

    @Transactional
    public void release(
            Long id,
            StockRequest request,
            UUID reservationKey
    ) {
        StoredStockReservation reservation =
                stockReservationRequestRepository
                        .findForUpdate(reservationKey)
                        .orElseThrow(() ->
                                new IdempotencyConflictException(
                                        "Stock reservation not found for key "
                                                + reservationKey
                                )
                        );

        boolean sameReservation =
                reservation.productId().equals(id)
                        && reservation.quantity().equals(request.quantity());

        if (!sameReservation) {
            throw new IdempotencyConflictException(
                    "Reservation key does not match stock release request"
            );
        }

        if (reservation.releasedAt() != null) {
            return;
        }

        Product product = productRepository.findById(id)
                .orElseThrow(() ->
                        new ProductNotFoundException(
                                "Product not found " + id
                        )
                );

        product.setStockQuantity(
                product.getStockQuantity() + request.quantity()
        );

        stockReservationRequestRepository.markReleased(
                reservationKey,
                OffsetDateTime.now()
        );
    }

        private ProductResponse toResponse(Product product) {
        return new ProductResponse(
                product.getId(),
                product.getName(),
                product.getDescription(),
                product.getPrice(),
                product.getStockQuantity(),
                product.getActive(),
                product.getCreatedAt()
        );
    }
    private ProductReservationResponse getPreviousReservation(
            UUID idempotencyKey,
            Long productId,
            Integer quantity
    ) {
        StoredStockReservation storedReservation =
                stockReservationRequestRepository
                        .findByIdempotencyKey(idempotencyKey)
                        .orElseThrow(() ->
                                new IllegalStateException(
                                        "Idempotency key not found: "
                                                + idempotencyKey
                                )
                        );

        boolean sameRequest =
                storedReservation.productId().equals(productId)
                        && storedReservation.quantity().equals(quantity);

        if (!sameRequest) {
            throw new IdempotencyConflictException(
                    "Idempotency key was already used for another stock reservation"
            );
        }

        return new ProductReservationResponse(
                storedReservation.productId(),
                storedReservation.quantity(),
                storedReservation.unitPrice()
        );
    }

}
