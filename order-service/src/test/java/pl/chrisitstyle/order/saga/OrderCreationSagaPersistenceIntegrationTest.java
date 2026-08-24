package pl.chrisitstyle.order.saga;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;

import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@DataJpaTest(
        properties = {
                "spring.jpa.hibernate.ddl-auto=validate"
        }
)
@AutoConfigureTestDatabase(
        replace = AutoConfigureTestDatabase.Replace.NONE
)
class OrderCreationSagaPersistenceIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:17");

    @Autowired
    private OrderCreationSagaRepository sagaRepository;

    @Autowired
    private OrderCreationSagaReservationRepository reservationRepository;

    @Autowired
    private EntityManager entityManager;

    @Test
    void shouldPersistSagaWithPlannedReservations() {

        OrderCreationSaga saga =
                OrderCreationSaga.start(123L);

        OrderCreationSagaReservation firstReservation =
                OrderCreationSagaReservation.plan(
                        saga.getId(),
                        42L,
                        2,
                        0
                );

        OrderCreationSagaReservation secondReservation =
                OrderCreationSagaReservation.plan(
                        saga.getId(),
                        84L,
                        1,
                        1
                );

        sagaRepository.saveAndFlush(saga);

        reservationRepository.saveAll(
                List.of(
                        secondReservation,
                        firstReservation
                )
        );

        reservationRepository.flush();

        entityManager.clear();

        OrderCreationSaga storedSaga =
                sagaRepository.findById(
                                saga.getId()
                        )
                        .orElseThrow();

        List<OrderCreationSagaReservation> storedReservations =
                reservationRepository
                        .findAllBySagaIdOrderByItemIndexAsc(
                                saga.getId()
                        );

        assertThat(storedSaga.getId())
                .isEqualTo(saga.getId());

        assertThat(storedSaga.getUserId())
                .isEqualTo(123L);

        assertThat(storedSaga.getStatus())
                .isEqualTo(
                        OrderCreationSagaStatus.STARTED
                );

        assertThat(storedSaga.getOrderId())
                .isNull();

        assertThat(storedSaga.getFailureReason())
                .isNull();

        assertThat(storedSaga.getCreatedAt())
                .isNotNull();

        assertThat(storedSaga.getUpdatedAt())
                .isNotNull();

        assertThat(storedReservations)
                .hasSize(2);

        OrderCreationSagaReservation storedFirst =
                storedReservations.get(0);

        OrderCreationSagaReservation storedSecond =
                storedReservations.get(1);

        assertThat(storedFirst.getSagaId())
                .isEqualTo(saga.getId());

        assertThat(storedFirst.getProductId())
                .isEqualTo(42L);

        assertThat(storedFirst.getQuantity())
                .isEqualTo(2);

        assertThat(storedFirst.getItemIndex())
                .isZero();

        assertThat(storedFirst.getReservationKey())
                .isNotNull();

        assertThat(storedFirst.getStatus())
                .isEqualTo(
                        SagaReservationStatus.PLANNED
                );

        assertThat(storedFirst.getUnitPrice())
                .isNull();

        assertThat(storedSecond.getProductId())
                .isEqualTo(84L);

        assertThat(storedSecond.getQuantity())
                .isEqualTo(1);

        assertThat(storedSecond.getItemIndex())
                .isEqualTo(1);

        assertThat(storedSecond.getReservationKey())
                .isNotNull();

        assertThat(storedSecond.getReservationKey())
                .isNotEqualTo(
                        storedFirst.getReservationKey()
                );

        assertThat(storedSecond.getStatus())
                .isEqualTo(
                        SagaReservationStatus.PLANNED
                );
    }

    @Test
    void shouldPersistSagaAndReservationStateTransitions() {

        OrderCreationSaga saga =
                OrderCreationSaga.start(123L);

        OrderCreationSagaReservation reservation =
                OrderCreationSagaReservation.plan(
                        saga.getId(),
                        42L,
                        2,
                        0
                );

        OrderCreationSaga managedSaga =
                sagaRepository.saveAndFlush(saga);

        OrderCreationSagaReservation managedReservation =
                reservationRepository.saveAndFlush(
                        reservation
                );

        managedSaga.markReservingStock();

        managedReservation.markReserved(
                new BigDecimal("19.99")
        );

        entityManager.flush();

        sagaRepository.flush();
        reservationRepository.flush();

        entityManager.clear();

        OrderCreationSaga storedSaga =
                sagaRepository.findById(
                                saga.getId()
                        )
                        .orElseThrow();

        OrderCreationSagaReservation storedReservation =
                reservationRepository
                        .findByReservationKey(
                                reservation.getReservationKey()
                        )
                        .orElseThrow();

        assertThat(storedSaga.getStatus())
                .isEqualTo(
                        OrderCreationSagaStatus.RESERVING_STOCK
                );

        assertThat(storedReservation.getStatus())
                .isEqualTo(
                        SagaReservationStatus.RESERVED
                );

        assertThat(storedReservation.getUnitPrice())
                .isEqualByComparingTo(
                        new BigDecimal("19.99")
                );

        assertThat(storedReservation.getUpdatedAt())
                .isAfterOrEqualTo(
                        storedReservation.getCreatedAt()
                );
    }
}