package cn.xixitravel.ride.knowledge;

import java.util.List;

public record KnowledgeSearchResponse(
        String query,
        String collection,
        List<KnowledgeHit> hits
) {
}
