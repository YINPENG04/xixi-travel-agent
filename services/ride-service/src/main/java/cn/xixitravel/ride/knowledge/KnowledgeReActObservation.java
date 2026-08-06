package cn.xixitravel.ride.knowledge;

import java.util.List;

public record KnowledgeReActObservation(
        String cycleId,
        int iteration,
        int maxIterations,
        boolean terminal,
        String stopReason,
        String query,
        String collection,
        String retrievalStatus,
        String recommendedNextAction,
        double topScore,
        double scoreGap,
        String observationReason,
        List<KnowledgeHit> hits
) {
}
