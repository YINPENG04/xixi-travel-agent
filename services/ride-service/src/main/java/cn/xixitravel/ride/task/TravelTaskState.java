package cn.xixitravel.ride.task;

import cn.xixitravel.ride.context.SessionPendingAction;
import cn.xixitravel.ride.intent.TravelIntent;

import java.time.Instant;

public record TravelTaskState(
        String taskId,
        String userId,
        String conversationId,
        TravelIntent intent,
        TravelTaskPhase phase,
        TravelTaskAction nextAction,
        SessionPendingAction pendingAction,
        String origin,
        String destination,
        String quoteId,
        String orderId,
        String lastOrderStatus,
        int failureCount,
        long version,
        boolean terminal,
        String terminalReason,
        Instant updatedAt
) {
}
