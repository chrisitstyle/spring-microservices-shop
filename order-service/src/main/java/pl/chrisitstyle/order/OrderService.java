package pl.chrisitstyle.order;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pl.chrisitstyle.order.exception.InvalidOrderStatusTransitionException;
import pl.chrisitstyle.order.exception.OrderCreationException;
import pl.chrisitstyle.order.exception.OrderDeletionException;
import pl.chrisitstyle.order.exception.OrderNotFoundException;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
public class OrderService {

    private final OrderRepository orderRepository;
    private final UserClient userClient;
    private final ProductClient productClient;
    private final OrderEventPublisher orderEventPublisher;
    @Transactional
    public OrderResponse create(CreateOrderRequest request) {
        UserResponse user = userClient.getUser(request.userId());

        if (!user.active()) {
            throw new OrderCreationException(
                    "User " + user.id() + " is inactive"
            );
        }

        Order order = new Order();
        order.setUserId(user.id());
        order.setStatus(OrderStatus.CREATED);

        BigDecimal totalAmount = BigDecimal.ZERO;
        List<StockReservation> reservations = new ArrayList<>();

        try {
            for (CreateOrderItemRequest itemRequest : request.items()) {
                ProductReservationResponse reservation =
                        productClient.reserve(
                                itemRequest.productId(),
                                itemRequest.quantity()
                        );

                reservations.add(
                        new StockReservation(
                                reservation.productId(),
                                reservation.quantity()
                        )
                );

                OrderItem orderItem = new OrderItem();
                orderItem.setProductId(reservation.productId());
                orderItem.setQuantity(reservation.quantity());
                orderItem.setUnitPrice(reservation.unitPrice());

                order.addItem(orderItem);

                BigDecimal itemTotal =
                        reservation.unitPrice().multiply(
                                BigDecimal.valueOf(reservation.quantity())
                        );

                totalAmount = totalAmount.add(itemTotal);
            }

            order.setTotalAmount(totalAmount);

            Order savedOrder = orderRepository.save(order);

            OrderCreatedEvent event = new OrderCreatedEvent(
                    savedOrder.getId(),
                    savedOrder.getUserId(),
                    savedOrder.getTotalAmount(),
                    savedOrder.getCreatedAt()
            );

            orderEventPublisher.publishOrderCreated(event);

            return toResponse(savedOrder);

        } catch (RuntimeException exception) {
            releaseReservations(reservations);
            throw exception;
        }
    }

    @Transactional(readOnly = true)
    public OrderResponse getById(Long id) {
        Order order = findById(id);

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
    public OrderResponse updateStatus(
            Long id,
            UpdateOrderStatusRequest request
    ) {
        Order order = findById(id);

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
        Order order = findById(id);

        if (order.getStatus() == OrderStatus.PAID
                || order.getStatus() == OrderStatus.COMPLETED) {
            throw new OrderDeletionException(
                    "Cannot delete order with status " + order.getStatus()
            );
        }

        /*
         * CANCELLED orders have already restored their stock
         * during the status transition.
         *
         * CREATED orders have not restored the stock yet,
         * so we need to release it before deleting the order.
         */
        if (order.getStatus() == OrderStatus.CREATED) {
            restoreStock(order);
        }

        orderRepository.delete(order);
    }

    private Order findById(Long id) {
        return orderRepository.findById(id)
                .orElseThrow(() -> new OrderNotFoundException(id));
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

    private void restoreStock(Order order) {
        order.getItems()
                .forEach(item ->
                        productClient.release(
                                item.getProductId(),
                                item.getQuantity()
                        )
                );
    }

    private void releaseReservations(
            List<StockReservation> reservations
    ) {
        /*
         * Compensate reservations in reverse order.
         *
         * A reserved
         * B reserved
         * C reservation failed
         *
         * B released
         * A released
         */
        for (int i = reservations.size() - 1; i >= 0; i--) {
            StockReservation reservation = reservations.get(i);

            productClient.release(
                    reservation.productId(),
                    reservation.quantity()
            );
        }
    }

    private OrderResponse toResponse(Order order) {
        List<OrderItemResponse> items = order.getItems()
                .stream()
                .map(item -> new OrderItemResponse(
                        item.getProductId(),
                        item.getQuantity(),
                        item.getUnitPrice(),
                        item.getUnitPrice().multiply(
                                BigDecimal.valueOf(item.getQuantity())
                        )
                ))
                .toList();

        return new OrderResponse(
                order.getId(),
                order.getUserId(),
                order.getStatus(),
                order.getTotalAmount(),
                order.getCreatedAt(),
                items
        );
    }

    private record StockReservation(
            Long productId,
            Integer quantity
    ) {
    }
}