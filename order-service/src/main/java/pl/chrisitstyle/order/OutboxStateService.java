package pl.chrisitstyle.order;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class OutboxStateService {

    private final OutboxEventRepository outboxEventRepository;

    @Transactional
    public boolean markPublished(UUID eventId, String workerId) {
        return outboxEventRepository.markPublished(
                eventId,
                workerId,
                OffsetDateTime.now()
        ) == 1;
    }

    @Transactional
    public void release(UUID eventId, String workerId) {
        outboxEventRepository.release(eventId, workerId);
    }
}
