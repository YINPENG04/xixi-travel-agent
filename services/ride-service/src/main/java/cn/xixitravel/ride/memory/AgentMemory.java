package cn.xixitravel.ride.memory;

import java.time.Instant;

public record AgentMemory(
        String memoryId,
        String userId,
        AgentMemoryCategory category,
        String key,
        String value,
        Instant updatedAt,
        long version
) {
}
