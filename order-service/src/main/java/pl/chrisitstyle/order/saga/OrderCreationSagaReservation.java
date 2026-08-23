package pl.chrisitstyle.order.saga;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "order_creation_saga_reservations")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OrderCreationSagaReservation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "saga_id", nullable = false)
    private UUID sagaId;

    @Column(name = "product_id", nullable = false)
    private Long productId;

    @Column(nullable = false)
    private Integer quantity;

    @Column(name = "reservation_key", nullable = false, unique = true)
    private UUID reservationKey;

    @Column(name = "item_index", nullable = false)
    private Integer itemIndex;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private SagaReservationStatus status;

    @Column(name = "unit_price", precision = 12, scale = 2)
    private BigDecimal unitPrice;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public static OrderCreationSagaReservation plan(
            UUID sagaId,
            Long productId,
            Integer quantity,
            Integer itemIndex
    ) {
        OrderCreationSagaReservation reservation =
                new OrderCreationSagaReservation();

        reservation.sagaId = Objects.requireNonNull(sagaId);
        reservation.productId = Objects.requireNonNull(productId);
        reservation.quantity = Objects.requireNonNull(quantity);
        reservation.itemIndex = Objects.requireNonNull(itemIndex);

        reservation.reservationKey = UUID.randomUUID();
        reservation.status = SagaReservationStatus.PLANNED;

        Instant now = Instant.now();
        reservation.createdAt = now;
        reservation.updatedAt = now;

        return reservation;
    }

    public void markReserved(BigDecimal unitPrice) {
        if (status != SagaReservationStatus.PLANNED) {
            throw new IllegalStateException(
                    "Cannot reserve saga step from status " + status
            );
        }

        this.unitPrice = Objects.requireNonNull(unitPrice);
        this.status = SagaReservationStatus.RESERVED;

        touch();
    }

    public void markReservationFailed() {
        if (status != SagaReservationStatus.PLANNED) {
            throw new IllegalStateException(
                    "Cannot fail saga reservation from status " + status
            );
        }

        this.status = SagaReservationStatus.RESERVATION_FAILED;

        touch();
    }

    public void markReleased() {
        if (status != SagaReservationStatus.RESERVED) {
            throw new IllegalStateException(
                    "Cannot release saga reservation from status " + status
            );
        }

        this.status = SagaReservationStatus.RELEASED;

        touch();
    }

    private void touch() {
        updatedAt = Instant.now();
    }
}
