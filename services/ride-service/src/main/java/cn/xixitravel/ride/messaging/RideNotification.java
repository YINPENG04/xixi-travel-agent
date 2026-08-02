package cn.xixitravel.ride.messaging;

import java.time.Instant;

public record RideNotification(
        String notificationId,
        String orderId,
        RideEventType type,
        String message,
        Instant createdAt
) {
}
