package pl.chrisitstyle.shop.exception;

import pl.chrisitstyle.shop.order.OrderStatus;

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
