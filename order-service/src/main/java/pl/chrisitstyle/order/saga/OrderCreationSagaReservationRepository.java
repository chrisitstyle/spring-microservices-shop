package pl.chrisitstyle.order.saga;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface OrderCreationSagaReservationRepository
        extends JpaRepository<OrderCreationSagaReservation, Long> {

    List<OrderCreationSagaReservation>
    findAllBySagaIdOrderByItemIndexAsc(UUID sagaId);

    Optional<OrderCreationSagaReservation>
    findByReservationKey(UUID reservationKey);
}
