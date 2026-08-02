package cn.xixitravel.ride.messaging;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "ride_outbox_events")
public class RideOutboxEntity {
    @Id
    @Column(name = "event_id", length = 36, nullable = false)
    private String eventId;

    @Column(name = "aggregate_id", length = 32, nullable = false)
    private String aggregateId;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", length = 32, nullable = false)
    private RideEventType eventType;

    @Column(nullable = false, columnDefinition = "LONGTEXT")
    private String payload;

    @Column(name = "delay_level", nullable = false)
    private int delayLevel;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_status", length = 16, nullable = false)
    private OutboxStatus status;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "available_at", nullable = false)
    private Instant availableAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(name = "last_error", length = 1000)
    private String lastError;

    protected RideOutboxEntity() {
    }

    public RideOutboxEntity(
            String eventId,
            String aggregateId,
            RideEventType eventType,
            String payload,
            int delayLevel,
            Instant createdAt
    ) {
        this.eventId = eventId;
        this.aggregateId = aggregateId;
        this.eventType = eventType;
        this.payload = payload;
        this.delayLevel = delayLevel;
        this.status = OutboxStatus.PENDING;
        this.attemptCount = 0;
        this.availableAt = createdAt;
        this.createdAt = createdAt;
    }

    public void markPublished(Instant publishedAt) {
        this.status = OutboxStatus.PUBLISHED;
        this.publishedAt = publishedAt;
        this.lastError = null;
    }

    public void recordFailure(String error, Instant retryAt, int maxAttempts) {
        attemptCount++;
        lastError = error == null ? "unknown RocketMQ publish error" : error.substring(0, Math.min(error.length(), 1000));
        if (attemptCount >= maxAttempts) {
            status = OutboxStatus.FAILED;
        } else {
            availableAt = retryAt;
        }
    }

    public String getEventId() {
        return eventId;
    }

    public String getAggregateId() {
        return aggregateId;
    }

    public RideEventType getEventType() {
        return eventType;
    }

    public String getPayload() {
        return payload;
    }

    public int getDelayLevel() {
        return delayLevel;
    }

    public OutboxStatus getStatus() {
        return status;
    }

    public int getAttemptCount() {
        return attemptCount;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
