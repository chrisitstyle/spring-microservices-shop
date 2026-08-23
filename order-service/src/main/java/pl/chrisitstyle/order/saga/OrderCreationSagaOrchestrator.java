package pl.chrisitstyle.order.saga;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import pl.chrisitstyle.order.CreateOrderItemRequest;
import pl.chrisitstyle.order.CreateOrderRequest;
import pl.chrisitstyle.order.OrderResponse;
import pl.chrisitstyle.order.ProductClient;
import pl.chrisitstyle.order.ProductReservationResponse;
import pl.chrisitstyle.order.exception.OrderCreationException;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class OrderCreationSagaOrchestrator {

    private final ProductClient productClient;
    private final OrderCreationSagaStateService sagaStateService;
    private final OrderCreationSagaCompletionService completionService;

    public OrderResponse create(
            Long userId,
            CreateOrderRequest request
    ) {
        OrderCreationSaga saga =
                sagaStateService.start(userId);

        UUID sagaId = saga.getId();

        sagaStateService.markReservingStock(sagaId);

        try {
            reserveStock(
                    sagaId,
                    request
            );

            sagaStateService.markStockReserved(
                    sagaId
            );

            return completionService.complete(
                    sagaId
            );

        } catch (RuntimeException exception) {

            compensate(
                    sagaId,
                    exception
            );

            throw exception;
        }
    }

    private void reserveStock(
            UUID sagaId,
            CreateOrderRequest request
    ) {
        int itemIndex = 0;

        for (CreateOrderItemRequest item
                : request.items()) {

            OrderCreationSagaReservation planned =
                    sagaStateService.planReservation(
                            sagaId,
                            item.productId(),
                            item.quantity(),
                            itemIndex
                    );

            try {
                ProductReservationResponse response =
                        productClient.reserve(
                                item.productId(),
                                item.quantity(),
                                planned.getReservationKey()
                        );

                sagaStateService.markReserved(
                        planned.getReservationKey(),
                        response.unitPrice()
                );

            } catch (OrderCreationException exception) {

                sagaStateService.markReservationFailed(
                        planned.getReservationKey()
                );

                throw exception;
            }

            itemIndex++;
        }
    }

    private void compensate(
            UUID sagaId,
            RuntimeException originalFailure
    ) {
        try {
            sagaStateService.markCompensating(
                    sagaId,
                    failureMessage(originalFailure)
            );
        } catch (RuntimeException stateFailure) {

            originalFailure.addSuppressed(
                    stateFailure
            );

            return;
        }

        List<OrderCreationSagaReservation> reservations =
                sagaStateService.getReservations(
                        sagaId
                );

        boolean unknownReservationOutcome = false;

        for (int i = reservations.size() - 1;
             i >= 0;
             i--) {

            OrderCreationSagaReservation reservation =
                    reservations.get(i);

            if (reservation.getStatus()
                    == SagaReservationStatus.PLANNED) {

                unknownReservationOutcome = true;
                continue;
            }

            if (reservation.getStatus()
                    != SagaReservationStatus.RESERVED) {

                continue;
            }

            try {
                productClient.release(
                        reservation.getProductId(),
                        reservation.getQuantity(),
                        reservation.getReservationKey()
                );

                sagaStateService.markReleased(
                        reservation.getReservationKey()
                );

            } catch (RuntimeException compensationFailure) {

                sagaStateService.markCompensationFailed(
                        sagaId,
                        "Stock compensation failed: "
                                + failureMessage(
                                compensationFailure
                        )
                );

                originalFailure.addSuppressed(
                        compensationFailure
                );

                return;
            }
        }

        if (unknownReservationOutcome) {

            sagaStateService.markCompensationFailed(
                    sagaId,
                    "At least one stock reservation "
                            + "has an unknown remote outcome"
            );

            return;
        }

        sagaStateService.markCompensated(
                sagaId
        );
    }

    private String failureMessage(
            RuntimeException exception
    ) {
        if (exception.getMessage() == null
                || exception.getMessage().isBlank()) {

            return exception.getClass()
                    .getSimpleName();
        }

        return exception.getMessage();
    }
}