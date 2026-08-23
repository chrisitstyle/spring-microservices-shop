package pl.chrisitstyle.order;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pl.chrisitstyle.order.exception.*;
import pl.chrisitstyle.order.saga.OrderCreationSagaOrchestrator;

import java.math.BigDecimal;
import java.util.List;

@Service
@RequiredArgsConstructor
public class OrderService {

    private final OrderRepository orderRepository;
    private final UserClient userClient;
    private final ProductClient productClient;
    private final OrderCreationSagaOrchestrator orderCreationSagaOrchestrator;

    public OrderResponse create(
            String keycloakSubject,
            CreateOrderRequest request
    ) {
        UserResponse user =
                userClient.getUserByKeycloakSubject(
                        keycloakSubject
                );

        if (!user.active()) {
            throw new OrderCreationException(
                    "User "
                            + user.id()
                            + " is inactive"
            );
        }

        return orderCreationSagaOrchestrator.create(
                user.id(),
                request
        );
    }

    @Transactional(readOnly = true)
    public OrderResponse getById(
            Long id,
            String keycloakSubject,
            boolean admin
    ) {
        Order order = findById(id);

        if (admin) {
            return toResponse(order);
        }

        UserResponse user = userClient.getUserByKeycloakSubject(keycloakSubject);

        if (!order.getUserId().equals(user.id())) {
            throw new OrderAccessDeniedException(id);
        }

        return toResponse(order);
    }

    @Transactional(readOnly = true)
    public List<OrderResponse> getAll() {
        return orderRepository.findAll()
                .stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<OrderResponse> getMyOrders(String keycloakSubject) {
        UserResponse user =
                userClient.getUserByKeycloakSubject(keycloakSubject);

        return orderRepository.findAllByUserId(user.id())
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
            case CREATED -> newStatus == OrderStatus.PAID
                    || newStatus == OrderStatus.CANCELLED;

            case PAID -> newStatus == OrderStatus.COMPLETED
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
                                item.getQuantity(),
                                item.getReservationKey()
                        )
                );
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
}