package cn.xixitravel.ride.memory;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "agent_memory_index_jobs")
public class AgentMemoryIndexJobEntity {
    @Id
    @Column(name = "job_id", length = 36, nullable = false)
    private String jobId;

    @Column(name = "memory_id", length = 36, nullable = false)
    private String memoryId;

    @Column(name = "user_id", length = 128, nullable = false)
    private String userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "operation_type", length = 16, nullable = false)
    private AgentMemoryIndexOperation operation;

    @Enumerated(EnumType.STRING)
    @Column(name = "memory_category", length = 32)
    private AgentMemoryCategory category;

    @Column(name = "memory_key", length = 64)
    private String memoryKey;

    @Column(name = "memory_value", length = 1000)
    private String memoryValue;

    @Column(name = "memory_version")
    private Long memoryVersion;

    @Column(name = "memory_updated_at")
    private Instant memoryUpdatedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "job_status", length = 16, nullable = false)
    private AgentMemoryIndexJobStatus status;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt;

    @Column(name = "last_error", length = 512)
    private String lastError;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected AgentMemoryIndexJobEntity() {
    }

    private AgentMemoryIndexJobEntity(
            String jobId,
            AgentMemory memory,
            AgentMemoryIndexOperation operation,
            Instant now
    ) {
        this.jobId = jobId;
        this.memoryId = memory.memoryId();
        this.userId = memory.userId();
        this.operation = operation;
        this.category = operation == AgentMemoryIndexOperation.UPSERT
                ? memory.category()
                : null;
        this.memoryKey = operation == AgentMemoryIndexOperation.UPSERT
                ? memory.key()
                : null;
        this.memoryValue = operation == AgentMemoryIndexOperation.UPSERT
                ? memory.value()
                : null;
        this.memoryVersion = operation == AgentMemoryIndexOperation.UPSERT
                ? memory.version()
                : null;
        this.memoryUpdatedAt = operation == AgentMemoryIndexOperation.UPSERT
                ? memory.updatedAt()
                : null;
        this.status = AgentMemoryIndexJobStatus.PENDING;
        this.nextAttemptAt = now;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public static AgentMemoryIndexJobEntity upsert(
            String jobId,
            AgentMemory memory,
            Instant now
    ) {
        return new AgentMemoryIndexJobEntity(
                jobId,
                memory,
                AgentMemoryIndexOperation.UPSERT,
                now
        );
    }

    public static AgentMemoryIndexJobEntity delete(
            String jobId,
            AgentMemory memory,
            Instant now
    ) {
        return new AgentMemoryIndexJobEntity(
                jobId,
                memory,
                AgentMemoryIndexOperation.DELETE,
                now
        );
    }

    public AgentMemory toMemory() {
        if (operation != AgentMemoryIndexOperation.UPSERT) {
            throw new IllegalStateException("delete job has no memory payload");
        }
        return new AgentMemory(
                memoryId,
                userId,
                category,
                memoryKey,
                memoryValue,
                memoryUpdatedAt,
                memoryVersion
        );
    }

    public void complete(Instant now) {
        status = AgentMemoryIndexJobStatus.COMPLETED;
        lastError = null;
        updatedAt = now;
    }

    public void retry(String error, Instant nextAttemptAt, Instant now) {
        attempts++;
        lastError = error == null ? "unknown error" : error.substring(0, Math.min(512, error.length()));
        this.nextAttemptAt = nextAttemptAt;
        updatedAt = now;
    }

    public String getJobId() {
        return jobId;
    }

    public String getMemoryId() {
        return memoryId;
    }

    public String getUserId() {
        return userId;
    }

    public AgentMemoryIndexOperation getOperation() {
        return operation;
    }

    public AgentMemoryIndexJobStatus getStatus() {
        return status;
    }

    public int getAttempts() {
        return attempts;
    }
}
