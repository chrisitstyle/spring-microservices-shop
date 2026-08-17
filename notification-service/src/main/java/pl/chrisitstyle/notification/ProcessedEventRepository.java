package pl.chrisitstyle.notification;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class ProcessedEventRepository {

    private final JdbcTemplate jdbcTemplate;

    public boolean tryRegister(Long orderId) {
        int insertedRows = jdbcTemplate.update("""
                INSERT INTO processed_order_events (order_id)
                VALUES (?)
                ON CONFLICT (order_id) DO NOTHING
                """,
                orderId
        );

        return insertedRows == 1;
    }
}
