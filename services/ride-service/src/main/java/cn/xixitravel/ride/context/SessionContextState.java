package cn.xixitravel.ride.context;

import java.time.Instant;

public record SessionContextState(
        String userId,
        String conversationId,
        String activeQuoteId,
        String activeOrderId,
        SessionPendingAction pendingAction,
        String taskSummary,
        long summarizedThrough,
        Instant updatedAt
) {
}
