package cn.xixitravel.ride.messaging;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;

@Entity
@Table(
        name = "ride_notifications",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_ride_notification_event",
                columnNames = "event_id"
        )
)
public class RideNotificationEntity {
    @Id
    @Column(name = "notification_id", length = 36, nullable = false)
    private String notificationId;

    @Column(name = "event_id", length = 36, nullable = false)
    private String eventId;

    @Column(name = "order_id", length = 32, nullable = false)
    private String orderId;

    @Column(name = "user_id", length = 128, nullable = false)
    private String userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "notification_type", length = 32, nullable = false)
    private RideEventType type;

    @Column(length = 500, nullable = false)
    private String message;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected RideNotificationEntity() {
    }

    public RideNotificationEntity(
            String notificationId,
            String eventId,
            String orderId,
            String userId,
            RideEventType type,
            String message,
            Instant createdAt
    ) {
        this.notificationId = notificationId;
        this.eventId = eventId;
        this.orderId = orderId;
        this.userId = userId;
        this.type = type;
        this.message = message;
        this.createdAt = createdAt;
    }

    public RideNotification toView() {
        return new RideNotification(notificationId, orderId, type, message, createdAt);
    }
}
