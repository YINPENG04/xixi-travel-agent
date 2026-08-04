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
        this.memoryId = memoryId;
        this.userId = userId;
        this.category = category;
        this.memoryKey = memoryKey;
        this.memoryValue = memoryValue;
        this.createdAt = now;
        this.updatedAt = now;
        this.memoryVersion = 1;
    }

    public void updateValue(String value, Instant now) {
        this.memoryValue = value;
        this.updatedAt = now;
        this.memoryVersion++;
    }

    public AgentMemory toView() {
        return new AgentMemory(
                memoryId,
                userId,
                category,
                memoryKey,
                memoryValue,
                updatedAt,
                memoryVersion
        );
    }
}
