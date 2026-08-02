package cn.xixitravel.ride.messaging;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;

@Entity
@Table(
        name = "ride_consumed_events",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_ride_consumed_event",
                columnNames = {"event_id", "consumer_name"}
        )
)
public class ConsumedEventEntity {
    @Id
    @Column(name = "consumer_event_id", length = 128, nullable = false)
    private String consumerEventId;

    @Column(name = "event_id", length = 36, nullable = false)
    private String eventId;

    @Column(name = "consumer_name", length = 64, nullable = false)
    private String consumerName;

    @Column(name = "consumed_at", nullable = false)
    private Instant consumedAt;

    protected ConsumedEventEntity() {
    }

    public ConsumedEventEntity(String eventId, String consumerName, Instant consumedAt) {
        this.consumerEventId = consumerName + ":" + eventId;
        this.eventId = eventId;
        this.consumerName = consumerName;
        this.consumedAt = consumedAt;
    }
}
