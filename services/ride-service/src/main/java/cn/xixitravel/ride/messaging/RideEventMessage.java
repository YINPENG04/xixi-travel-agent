package cn.xixitravel.ride.messaging;

import cn.xixitravel.ride.domain.RideStatus;

import java.time.Instant;

public record RideEventMessage(
        String eventId,
        String orderId,
        String userId,
        RideEventType eventType,
        RideStatus status,
        Instant occurredAt
) {
}
