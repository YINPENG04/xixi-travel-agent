package cn.xixitravel.ride.messaging;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.List;

@Component
@ConditionalOnProperty(prefix = "xixi.messaging", name = "enabled", havingValue = "true")
public class RideOutboxPublisher {
    private final RideOutboxRepository outboxRepository;
    private final OutboxPublishService publishService;
    private final Clock clock;

    public RideOutboxPublisher(
            RideOutboxRepository outboxRepository,
            OutboxPublishService publishService,
            Clock clock
    ) {
        this.outboxRepository = outboxRepository;
        this.publishService = publishService;
        this.clock = clock;
    }

    @Scheduled(
            initialDelayString = "${xixi.messaging.outbox-poll-delay-ms:1000}",
            fixedDelayString = "${xixi.messaging.outbox-poll-delay-ms:1000}"
    )
    public void publishPendingEvents() {
        List<String> eventIds = outboxRepository.findPublishableIds(
                OutboxStatus.PENDING,
                clock.instant(),
                PageRequest.of(0, 50)
        );
        eventIds.forEach(publishService::publishOne);
    }
}
