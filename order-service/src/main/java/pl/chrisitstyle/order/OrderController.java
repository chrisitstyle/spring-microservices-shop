package pl.chrisitstyle.order;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/orders")
@RequiredArgsConstructor
public class OrderController {

    private final OrderService orderService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public OrderResponse create(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody CreateOrderRequest request
    ) {
        return orderService.create(
                jwt.getSubject(),
                request
        );
    }

    @GetMapping
    public List<OrderResponse> getAll() {
        return orderService.getAll();
    }

    @GetMapping("/{id}")
    public OrderResponse getById(
            @PathVariable Long id,
            @AuthenticationPrincipal Jwt jwt,
            Authentication authentication
    ) {
        boolean admin = authentication.getAuthorities()
                .stream()
                .anyMatch(authority ->
                        authority.getAuthority().equals("ROLE_ADMIN")
                );

        return orderService.getById(
                id,
                jwt.getSubject(),
                admin
        );
    }

    @GetMapping("/me")
    public List<OrderResponse> getMyOrders(
            @AuthenticationPrincipal Jwt jwt
    ) {
        return orderService.getMyOrders(
                jwt.getSubject()
        );
    }

    @PatchMapping("/{id}/status")
    public OrderResponse updateStatus(
            @PathVariable Long id,
            @Valid @RequestBody UpdateOrderStatusRequest request
    ) {
        return orderService.updateStatus(id, request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(
            @PathVariable Long id
    ) {
        orderService.delete(id);
    }
}