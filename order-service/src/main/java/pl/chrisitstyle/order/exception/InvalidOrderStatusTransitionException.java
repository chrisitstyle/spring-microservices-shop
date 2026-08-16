package pl.chrisitstyle.order.exception;

import pl.chrisitstyle.order.OrderStatus;

public class InvalidOrderStatusTransitionException extends RuntimeException {

    public InvalidOrderStatusTransitionException(
            OrderStatus currentStatus,
            OrderStatus newStatus
    ) {
        super(
                "Cannot change order status from "
                        + currentStatus
                        + " to "
                        + newStatus
        );
    }
}
