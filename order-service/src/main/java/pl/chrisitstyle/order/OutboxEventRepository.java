package pl.chrisitstyle.order;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, UUID> {

    @Query(
            value = """
                    SELECT *
                    FROM outbox_events
                    WHERE status = 'PENDING'
                    ORDER BY created_at
                    FOR UPDATE SKIP LOCKED
                    LIMIT :limit
                    """,
            nativeQuery = true
    )
    List<OutboxEvent> findPendingForProcessing(@Param("limit") int limit);

    @Modifying
    @Query(
            value = """
                    UPDATE outbox_events
                    SET status = 'PUBLISHED',
                        published_at = :publishedAt,
                        locked_at = NULL,
                        locked_by = NULL
                    WHERE id = :eventId
                      AND status = 'PROCESSING'
                      AND locked_by = :workerId
                    """,
            nativeQuery = true
    )
    int markPublished(
            @Param("eventId") UUID eventId,
            @Param("workerId") String workerId,
            @Param("publishedAt") OffsetDateTime publishedAt
    );

    @Modifying
    @Query(
            value = """
                    UPDATE outbox_events
                    SET status = 'PENDING',
                        locked_at = NULL,
                        locked_by = NULL
                    WHERE id = :eventId
                      AND status = 'PROCESSING'
                      AND locked_by = :workerId
                    """,
            nativeQuery = true
    )
    int release(
            @Param("eventId") UUID eventId,
            @Param("workerId") String workerId
    );

    @Modifying
    @Query(
            value = """
                    UPDATE outbox_events
                    SET status = 'PENDING',
                        locked_at = NULL,
                        locked_by = NULL
                    WHERE status = 'PROCESSING'
                      AND locked_at < CURRENT_TIMESTAMP - INTERVAL '5 minutes'
                    """,
            nativeQuery = true
    )
    int releaseStaleEvents();
}
