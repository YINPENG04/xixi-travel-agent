package cn.xixitravel.ride.memory;

import java.time.Instant;

public record AgentMemoryAudit(
        String auditId,
        String memoryId,
        String userId,
        AgentMemoryCategory category,
        String key,
        AgentMemoryAuditAction action,
        long memoryVersion,
        Double previousConfidence,
        Double newConfidence,
        Instant previousExpiresAt,
        Instant newExpiresAt,
        String actor,
        String reason,
        Instant occurredAt
) {
}
