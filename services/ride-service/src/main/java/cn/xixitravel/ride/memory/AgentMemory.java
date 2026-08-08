package cn.xixitravel.ride.memory;

import java.time.Instant;

public record AgentMemory(
        String memoryId,
        String userId,
        AgentMemoryCategory category,
        String key,
        String value,
        Instant updatedAt,
        long version,
        double confidence,
        Instant expiresAt,
        AgentMemoryStatus status
) {
    public AgentMemory(
            String memoryId,
            String userId,
            AgentMemoryCategory category,
            String key,
            String value,
            Instant updatedAt,
            long version
    ) {
        this(
                memoryId,
                userId,
                category,
                key,
                value,
                updatedAt,
                version,
                1.0,
                null,
                AgentMemoryStatus.ACTIVE
        );
    }
}
