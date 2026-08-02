package cn.xixitravel.ride.knowledge;

public record KnowledgeHit(
        long id,
        String category,
        String title,
        String content,
        double score
) {
}
