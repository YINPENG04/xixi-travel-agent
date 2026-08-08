package cn.xixitravel.ride.memory;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;

import java.time.Instant;

@Entity
@Table(
        name = "agent_user_memories",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_agent_memory_user_key",
                columnNames = {"user_id", "memory_category", "memory_key"}
        )
)
public class AgentMemoryEntity {
    @Id
    @Column(name = "memory_id", length = 36, nullable = false)
    private String memoryId;

    @Column(name = "user_id", length = 128, nullable = false)
    private String userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "memory_category", length = 32, nullable = false)
    private AgentMemoryCategory category;

    @Column(name = "memory_key", length = 64, nullable = false)
    private String memoryKey;

    @Column(name = "memory_value", length = 1000, nullable = false)
    private String memoryValue;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "memory_version", nullable = false)
    private long memoryVersion;

    @Column(nullable = false)
    private double confidence;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "memory_status", length = 16, nullable = false)
    private AgentMemoryStatus status;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    @Version
    @Column(name = "lock_version", nullable = false)
    private long lockVersion;

    protected AgentMemoryEntity() {
    }

    public AgentMemoryEntity(
            String memoryId,
            String userId,
            AgentMemoryCategory category,
            String memoryKey,
            String memoryValue,
            Instant now
    ) {
        this(memoryId, userId, category, memoryKey, memoryValue, now, 1.0, null);
    }

    public AgentMemoryEntity(
            String memoryId,
            String userId,
            AgentMemoryCategory category,
            String memoryKey,
            String memoryValue,
            Instant now,
            double confidence,
            Instant expiresAt
    ) {
        this.memoryId = memoryId;
        this.userId = userId;
        this.category = category;
        this.memoryKey = memoryKey;
        this.memoryValue = memoryValue;
        this.createdAt = now;
        this.updatedAt = now;
        this.memoryVersion = 1;
        this.confidence = confidence;
        this.expiresAt = expiresAt;
        this.status = AgentMemoryStatus.ACTIVE;
    }

    public void updateValue(String value, Instant now) {
        update(value, confidence, expiresAt, now);
    }

    public void update(String value, double confidence, Instant expiresAt, Instant now) {
        this.memoryValue = value;
        this.confidence = confidence;
        this.expiresAt = expiresAt;
        this.status = AgentMemoryStatus.ACTIVE;
        this.deletedAt = null;
        this.updatedAt = now;
        this.memoryVersion++;
    }

    public void refreshMetadata(double confidence, Instant expiresAt, Instant now) {
        this.confidence = confidence;
        this.expiresAt = expiresAt;
        this.status = AgentMemoryStatus.ACTIVE;
        this.deletedAt = null;
        this.updatedAt = now;
        this.memoryVersion++;
    }

    public void expire(Instant now) {
        if (status != AgentMemoryStatus.ACTIVE) {
            return;
        }
        status = AgentMemoryStatus.EXPIRED;
        updatedAt = now;
        memoryVersion++;
    }

    public void delete(Instant now) {
        if (status == AgentMemoryStatus.DELETED) {
            return;
        }
        status = AgentMemoryStatus.DELETED;
        deletedAt = now;
        updatedAt = now;
        memoryVersion++;
    }

    public boolean isActiveAt(Instant now) {
        return status == AgentMemoryStatus.ACTIVE
                && (expiresAt == null || expiresAt.isAfter(now));
    }

    public AgentMemory toView() {
        return new AgentMemory(
                memoryId,
                userId,
                category,
                memoryKey,
                memoryValue,
                updatedAt,
                memoryVersion,
                confidence,
                expiresAt,
                status
        );
    }

    public String getMemoryId() {
        return memoryId;
    }

    public String getUserId() {
        return userId;
    }

    public AgentMemoryCategory getCategory() {
        return category;
    }

    public String getMemoryKey() {
        return memoryKey;
    }

    public String getMemoryValue() {
        return memoryValue;
    }

    public long getMemoryVersion() {
        return memoryVersion;
    }

    public double getConfidence() {
        return confidence;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public AgentMemoryStatus getStatus() {
        return status;
    }
}
