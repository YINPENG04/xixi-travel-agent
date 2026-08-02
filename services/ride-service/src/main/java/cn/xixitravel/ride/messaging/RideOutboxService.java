package cn.xixitravel.ride.messaging;

import cn.xixitravel.ride.persistence.RideOrderEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

@Service
public class RideOutboxService {
    private final RideOutboxRepository outboxRepository;
    private final RideEventCodec eventCodec;
    private final Clock clock;

    public RideOutboxService(
            RideOutboxRepository outboxRepository,
            RideEventCodec eventCodec,
            Clock clock
    ) {
        this.outboxRepository = outboxRepository;
        this.eventCodec = eventCodec;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public String append(
            RideOrderEntity order,
            RideEventType eventType,
            int delayLevel
    ) {
        Instant now = clock.instant();
        String eventId = UUID.randomUUID().toString();
        RideEventMessage message = new RideEventMessage(
                eventId,
                order.getOrderId(),
                order.getUserId(),
                eventType,
                order.getStatus(),
                now
        );
        outboxRepository.save(new RideOutboxEntity(
                eventId,
                order.getOrderId(),
                eventType,
                eventCodec.encode(message),
                Math.max(delayLevel, 0),
                now
        ));
        return eventId;
    }
}
