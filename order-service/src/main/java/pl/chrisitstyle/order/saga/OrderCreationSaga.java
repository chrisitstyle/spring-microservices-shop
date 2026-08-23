package pl.chrisitstyle.order.saga;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.EnumSet;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "order_creation_sagas")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OrderCreationSaga {

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "order_id")
    private Long orderId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private OrderCreationSagaStatus status;

    @Column(name = "failure_reason", length = 1000)
    private String failureReason;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public static OrderCreationSaga start(Long userId) {
        OrderCreationSaga saga = new OrderCreationSaga();

        saga.id = UUID.randomUUID();
        saga.userId = Objects.requireNonNull(userId);
        saga.status = OrderCreationSagaStatus.STARTED;

        Instant now = Instant.now();
        saga.createdAt = now;
        saga.updatedAt = now;

        return saga;
    }

    public void markReservingStock() {
        transition(
                OrderCreationSagaStatus.STARTED,
                OrderCreationSagaStatus.RESERVING_STOCK
        );
    }

    public void markStockReserved() {
        transition(
                OrderCreationSagaStatus.RESERVING_STOCK,
                OrderCreationSagaStatus.STOCK_RESERVED
        );
    }

    public void markCompletingOrder() {
        transition(
                OrderCreationSagaStatus.STOCK_RESERVED,
                OrderCreationSagaStatus.COMPLETING_ORDER
        );
    }

    public void markCompleted(Long orderId) {
        requireStatus(OrderCreationSagaStatus.COMPLETING_ORDER);

        this.orderId = Objects.requireNonNull(orderId);
        this.status = OrderCreationSagaStatus.COMPLETED;
        this.failureReason = null;

        touch();
    }

    public void markFailed(String reason) {
        if (status.isTerminal()) {
            throw new IllegalStateException(
                    "Cannot fail terminal saga " + id
            );
        }

        this.status = OrderCreationSagaStatus.FAILED;
        this.failureReason = reason;

        touch();
    }

    public void markCompensating(String reason) {
        if (!EnumSet.of(
                OrderCreationSagaStatus.STARTED,
                OrderCreationSagaStatus.RESERVING_STOCK,
                OrderCreationSagaStatus.STOCK_RESERVED,
                OrderCreationSagaStatus.COMPLETING_ORDER,
                OrderCreationSagaStatus.COMPENSATION_FAILED
        ).contains(status)) {

            throw new IllegalStateException(
                    "Cannot start compensation from status " + status
            );
        }

        this.status = OrderCreationSagaStatus.COMPENSATING;
        this.failureReason = reason;

        touch();
    }

    public void markCompensated() {
        requireStatus(OrderCreationSagaStatus.COMPENSATING);

        this.status = OrderCreationSagaStatus.COMPENSATED;

        touch();
    }

    public void markCompensationFailed(String reason) {
        requireStatus(OrderCreationSagaStatus.COMPENSATING);

        this.status = OrderCreationSagaStatus.COMPENSATION_FAILED;
        this.failureReason = reason;

        touch();
    }

    private void transition(
            OrderCreationSagaStatus expected,
            OrderCreationSagaStatus next
    ) {
        requireStatus(expected);

        this.status = next;

        touch();
    }

    private void requireStatus(OrderCreationSagaStatus expected) {
        if (status != expected) {
            throw new IllegalStateException(
                    "Expected saga status "
                            + expected
                            + " but was "
                            + status
            );
        }
    }

    private void touch() {
        updatedAt = Instant.now();
    }

    void recordProgress() {
        touch();
    }
}
