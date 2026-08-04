package cn.xixitravel.ride.memory;

public record IndexedMemoryHit(
        String memoryId,
        long memoryVersion,
        double score
) {
}
