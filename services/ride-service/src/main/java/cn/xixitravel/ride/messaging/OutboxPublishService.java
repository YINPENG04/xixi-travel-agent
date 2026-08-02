package cn.xixitravel.ride.messaging;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

@Service
@ConditionalOnProperty(prefix = "xixi.messaging", name = "enabled", havingValue = "true")
public class OutboxPublishService {
    private static final int MAX_ATTEMPTS = 10;

    private final RideOutboxRepository outboxRepository;
    private final RocketMqEventSender eventSender;
    private final Clock clock;

    public OutboxPublishService(
            RideOutboxRepository outboxRepository,
            RocketMqEventSender eventSender,
            Clock clock
    ) {
        this.outboxRepository = outboxRepository;
        this.eventSender = eventSender;
        this.clock = clock;
    }

    @Transactional
    public void publishOne(String eventId) {
        RideOutboxEntity event = outboxRepository.findForUpdate(eventId).orElse(null);
        if (event == null || event.getStatus() != OutboxStatus.PENDING) {
            return;
        }

        Instant now = clock.instant();
        try {
            eventSender.send(event);
            event.markPublished(now);
        } catch (RuntimeException exception) {
            long retrySeconds = Math.min(60, 1L << Math.min(event.getAttemptCount(), 6));
            event.recordFailure(
                    exception.getMessage(),
                    now.plus(Duration.ofSeconds(retrySeconds)),
                    MAX_ATTEMPTS
            );
        }
    }
}
