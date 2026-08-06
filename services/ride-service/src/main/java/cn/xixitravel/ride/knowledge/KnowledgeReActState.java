package cn.xixitravel.ride.knowledge;

import java.time.Instant;

record KnowledgeReActState(
        String cycleId,
        String userId,
        String conversationId,
        String lastQuery,
        int iteration,
        boolean terminal,
        Instant updatedAt
) {
}
