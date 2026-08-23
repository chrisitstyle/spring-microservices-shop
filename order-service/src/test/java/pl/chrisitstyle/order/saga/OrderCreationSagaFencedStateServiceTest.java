package pl.chrisitstyle.order.saga;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import pl.chrisitstyle.order.exception.SagaRecoveryFencingException;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderCreationSagaFencedStateServiceTest {

    @Mock
    private NamedParameterJdbcTemplate jdbcTemplate;

    @InjectMocks
    private OrderCreationSagaFencedStateService service;

    @Test
    void shouldRejectStateChangeWhenWorkerIsFencedOut() {
        UUID sagaId = UUID.randomUUID();

        when(
                jdbcTemplate.update(
                        anyString(),
                        any(SqlParameterSource.class)
                )
        ).thenReturn(0);

        assertThrows(
                SagaRecoveryFencingException.class,
                () -> service.markCompensated(
                        sagaId,
                        "worker-A",
                        1L
                )
        );
    }

    @Test
    void shouldAcceptStateChangeWhenCurrentWorkerOwnsSaga() {
        UUID sagaId = UUID.randomUUID();

        when(
                jdbcTemplate.update(
                        anyString(),
                        any(SqlParameterSource.class)
                )
        ).thenReturn(1);

        assertDoesNotThrow(
                () -> service.markCompensated(
                        sagaId,
                        "worker-B",
                        2L
                )
        );
    }
}