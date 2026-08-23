package pl.chrisitstyle.order.saga;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface OrderCreationSagaRepository
        extends JpaRepository<OrderCreationSaga, UUID> {

    List<OrderCreationSaga> findAllByStatusIn(
            Collection<OrderCreationSagaStatus> statuses
    );

    List<OrderCreationSaga> findAllByStatusInAndUpdatedAtBefore(
            Collection<OrderCreationSagaStatus> statuses,
            Instant updatedAt
    );
}
