package cn.xixitravel.ride.memory;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "agent_memory_audits")
public class AgentMemoryAuditEntity {
    @Id
    @Column(name = "audit_id", length = 36, nullable = false)
    private String auditId;

    @Column(name = "memory_id", length = 36, nullable = false)
    private String memoryId;

    @Column(name = "user_id", length = 128, nullable = false)
    private String userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "memory_category", length = 32, nullable = false)
    private AgentMemoryCategory category;

    @Column(name = "memory_key", length = 64, nullable = false)
    private String memoryKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "action_type", length = 32, nullable = false)
    private AgentMemoryAuditAction action;

    @Column(name = "memory_version", nullable = false)
    private long memoryVersion;

    @Column(name = "previous_value_hash", length = 64)
    private String previousValueHash;

    @Column(name = "new_value_hash", length = 64)
    private String newValueHash;

    @Column(name = "previous_confidence")
    private Double previousConfidence;

    @Column(name = "new_confidence")
    private Double newConfidence;

    @Column(name = "previous_expires_at")
    private Instant previousExpiresAt;

    @Column(name = "new_expires_at")
    private Instant newExpiresAt;

    @Column(length = 32, nullable = false)
    private String actor;

    @Column(length = 255, nullable = false)
    private String reason;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    protected AgentMemoryAuditEntity() {
    }

    public AgentMemoryAuditEntity(
            String auditId,
            AgentMemoryEntity memory,
            AgentMemoryAuditAction action,
            String previousValueHash,
            String newValueHash,
            Double previousConfidence,
            Double newConfidence,
            Instant previousExpiresAt,
            Instant newExpiresAt,
            String actor,
            String reason,
            Instant occurredAt
    ) {
        this.auditId = auditId;
        this.memoryId = memory.getMemoryId();
        this.userId = memory.getUserId();
        this.category = memory.getCategory();
        this.memoryKey = memory.getMemoryKey();
        this.action = action;
        this.memoryVersion = memory.getMemoryVersion();
        this.previousValueHash = previousValueHash;
        this.newValueHash = newValueHash;
        this.previousConfidence = previousConfidence;
        this.newConfidence = newConfidence;
        this.previousExpiresAt = previousExpiresAt;
        this.newExpiresAt = newExpiresAt;
        this.actor = actor;
        this.reason = reason;
        this.occurredAt = occurredAt;
    }

    public AgentMemoryAudit toView() {
        return new AgentMemoryAudit(
                auditId,
                memoryId,
                userId,
                category,
                memoryKey,
                action,
                memoryVersion,
                previousConfidence,
                newConfidence,
                previousExpiresAt,
                newExpiresAt,
                actor,
                reason,
                occurredAt
        );
    }
}
