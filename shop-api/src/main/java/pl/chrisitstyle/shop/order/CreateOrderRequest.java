package pl.chrisitstyle.shop.order;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;

public record CreateOrderRequest(

        @NotNull
        Long userId,

        @NotEmpty
        List<@Valid CreateOrderItemRequest> items

) {
}
