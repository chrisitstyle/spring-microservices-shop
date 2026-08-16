package pl.chrisitstyle.shop.order;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pl.chrisitstyle.shop.exception.*;
import pl.chrisitstyle.shop.product.ProductRepository;
import pl.chrisitstyle.shop.product.domain.Product;
import pl.chrisitstyle.shop.user.User;
import pl.chrisitstyle.shop.user.UserRepository;

import java.math.BigDecimal;
import java.util.List;

@Service
@RequiredArgsConstructor
public class OrderService {

    private final OrderRepository orderRepository;
    private final UserRepository userRepository;
    private final ProductRepository productRepository;

    @Transactional
    public OrderResponse create(CreateOrderRequest request) {

        User user = userRepository.findById(request.userId())
                .orElseThrow(() -> new UserNotFoundException(request.userId()));

        if (!user.getActive()) {
            throw new OrderCreationException(
                    "User " + user.getId() + " is inactive"
            );
        }

        Order order = new Order();
        order.setUser(user);
        order.setStatus(OrderStatus.CREATED);

        BigDecimal totalAmount = BigDecimal.ZERO;

        for (CreateOrderItemRequest itemRequest : request.items()) {

            Product product = productRepository
                    .findById(itemRequest.productId())
                    .orElseThrow(() ->
                            new ProductNotFoundException("Product not found " + itemRequest.productId())
                    );

            if (!product.getActive()) {
                throw new OrderCreationException(
                        "Product " + product.getId() + " is inactive"
                );
            }

            if (product.getStockQuantity() < itemRequest.quantity()) {
                throw new OrderCreationException(
                        "Insufficient stock for product " + product.getId()
                );
            }

            BigDecimal unitPrice = product.getPrice();

            BigDecimal itemTotal = unitPrice.multiply(
                    BigDecimal.valueOf(itemRequest.quantity())
            );

            OrderItem orderItem = new OrderItem();
            orderItem.setProduct(product);
            orderItem.setQuantity(itemRequest.quantity());
            orderItem.setUnitPrice(unitPrice);

            order.addItem(orderItem);

            product.setStockQuantity(
                    product.getStockQuantity() - itemRequest.quantity()
            );

            totalAmount = totalAmount.add(itemTotal);
        }

        order.setTotalAmount(totalAmount);

        Order savedOrder = orderRepository.save(order);

        return toResponse(savedOrder);
    }

    @Transactional(readOnly = true)
    public OrderResponse getById(Long id) {
        Order order = orderRepository.findById(id)
                .orElseThrow(() -> new OrderNotFoundException(id));

        return toResponse(order);
    }

    @Transactional(readOnly = true)
    public List<OrderResponse> getAll() {
        return orderRepository.findAll()
                .stream()
                .map(this::toResponse)
                .toList();
    }


    @Transactional
    public OrderResponse updateStatus(Long id, UpdateOrderStatusRequest request) {
        Order order = orderRepository.findById(id)
                .orElseThrow(() -> new OrderNotFoundException(id));

        OrderStatus currentStatus = order.getStatus();
        OrderStatus newStatus = request.status();

        validateStatusTransition(currentStatus, newStatus);

        if (newStatus == OrderStatus.CANCELLED) {
            restoreStock(order);
        }

        order.setStatus(newStatus);

        return toResponse(order);
    }

    @Transactional
    public void delete(Long id) {
        Order order = orderRepository.findById(id)
                .orElseThrow(() -> new OrderNotFoundException(id));

        if (order.getStatus() == OrderStatus.PAID
                || order.getStatus() == OrderStatus.COMPLETED) {
            throw new OrderDeletionException(
                    "Cannot delete order with status " + order.getStatus()
            );
        }

        if (order.getStatus() == OrderStatus.CREATED) {
            restoreStock(order);
        }

        orderRepository.delete(order);
    }

    private void restoreStock(Order order) {
        order.getItems().forEach(item -> {
            Product product = item.getProduct();

            product.setStockQuantity(
                    product.getStockQuantity() + item.getQuantity()
            );
        });
    }

    private void validateStatusTransition(
            OrderStatus currentStatus,
            OrderStatus newStatus
    ) {
        boolean validTransition = switch (currentStatus) {
            case CREATED ->
                    newStatus == OrderStatus.PAID
                            || newStatus == OrderStatus.CANCELLED;

            case PAID ->
                    newStatus == OrderStatus.COMPLETED
                            || newStatus == OrderStatus.CANCELLED;

            case COMPLETED, CANCELLED -> false;
        };

        if (!validTransition) {
            throw new InvalidOrderStatusTransitionException(
                    currentStatus,
                    newStatus
            );
        }
    }
    private OrderResponse toResponse(Order order) {

        var items = order.getItems()
                .stream()
                .map(item -> new OrderItemResponse(
                        item.getProduct().getId(),
                        item.getQuantity(),
                        item.getUnitPrice(),
                        item.getUnitPrice().multiply(
                                BigDecimal.valueOf(item.getQuantity())
                        )
                ))
                .toList();

        return new OrderResponse(
                order.getId(),
                order.getUser().getId(),
                order.getStatus(),
                order.getTotalAmount(),
                order.getCreatedAt(),
                items
        );
    }
}
