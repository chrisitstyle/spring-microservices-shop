package pl.chrisitstyle.order.saga;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pl.chrisitstyle.order.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class OrderCreationSagaCompletionService {

    private final OrderCreationSagaRepository sagaRepository;
    private final OrderCreationSagaReservationRepository reservationRepository;

    private final OrderRepository orderRepository;
    private final OutboxService outboxService;

    @Transactional
    public OrderResponse complete(UUID sagaId) {
        OrderCreationSaga saga = sagaRepository
                .findById(sagaId)
                .orElseThrow(() ->
                        new IllegalStateException(
                                "Order creation saga "
                                        + sagaId
                                        + " not found"
                        )
                );

        if (saga.getStatus()
                != OrderCreationSagaStatus.STOCK_RESERVED) {

            throw new IllegalStateException(
                    "Cannot complete saga "
                            + sagaId
                            + " from status "
                            + saga.getStatus()
            );
        }

        List<OrderCreationSagaReservation> reservations =
                reservationRepository
                        .findAllBySagaIdOrderByItemIndexAsc(
                                sagaId
                        );

        if (reservations.isEmpty()) {
            throw new IllegalStateException(
                    "Saga " + sagaId
                            + " has no reservations"
            );
        }

        boolean allReserved = reservations.stream()
                .allMatch(reservation ->
                        reservation.getStatus()
                                == SagaReservationStatus.RESERVED
                );

        if (!allReserved) {
            throw new IllegalStateException(
                    "Saga "
                            + sagaId
                            + " contains unfinished reservations"
            );
        }

        saga.markCompletingOrder();

        Order order = new Order();

        order.setUserId(saga.getUserId());
        order.setStatus(OrderStatus.CREATED);

        BigDecimal totalAmount = BigDecimal.ZERO;

        for (OrderCreationSagaReservation reservation
                : reservations) {

            OrderItem item = new OrderItem();

            item.setProductId(
                    reservation.getProductId()
            );

            item.setQuantity(
                    reservation.getQuantity()
            );

            item.setUnitPrice(
                    reservation.getUnitPrice()
            );

            item.setReservationKey(
                    reservation.getReservationKey()
            );

            order.addItem(item);

            BigDecimal itemTotal =
                    reservation.getUnitPrice()
                            .multiply(
                                    BigDecimal.valueOf(
                                            reservation.getQuantity()
                                    )
                            );

            totalAmount =
                    totalAmount.add(itemTotal);
        }

        order.setTotalAmount(totalAmount);

        Order savedOrder =
                orderRepository.save(order);

        /*
         * Force SQL execution while we are still inside
         * the same local transaction.
         *
         * If anything after this fails, the transaction
         * is still rolled back as a whole.
         */
        orderRepository.flush();

        OrderCreatedEvent event =
                new OrderCreatedEvent(
                        savedOrder.getId(),
                        savedOrder.getUserId(),
                        savedOrder.getTotalAmount(),
                        savedOrder.getCreatedAt()
                );

        outboxService.saveOrderCreatedEvent(event);

        saga.markCompleted(savedOrder.getId());

        return toResponse(savedOrder);
    }

    private OrderResponse toResponse(Order order) {
        List<OrderItemResponse> items =
                order.getItems()
                        .stream()
                        .map(item ->
                                new OrderItemResponse(
                                        item.getProductId(),
                                        item.getQuantity(),
                                        item.getUnitPrice(),
                                        item.getUnitPrice()
                                                .multiply(
                                                        BigDecimal.valueOf(
                                                                item.getQuantity()
                                                        )
                                                )
                                )
                        )
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
