package cn.xixitravel.ride.memory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class AgentMemoryIndexJobService {
    private static final Logger log = LoggerFactory.getLogger(AgentMemoryIndexJobService.class);
    private static final Duration BASE_RETRY_DELAY = Duration.ofSeconds(5);
    private static final Duration MAX_RETRY_DELAY = Duration.ofMinutes(5);

    private final AgentMemoryIndexJobRepository repository;
    private final AgentMemoryIndexClient indexClient;
    private final Clock clock;
    private final TransactionTemplate transactionTemplate;

    public AgentMemoryIndexJobService(
            AgentMemoryIndexJobRepository repository,
            AgentMemoryIndexClient indexClient,
            Clock clock,
            org.springframework.transaction.PlatformTransactionManager transactionManager
    ) {
        this.repository = repository;
        this.indexClient = indexClient;
        this.clock = clock;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Transactional
    public String enqueueUpsert(AgentMemory memory) {
        String jobId = UUID.randomUUID().toString();
        repository.save(AgentMemoryIndexJobEntity.upsert(jobId, memory, clock.instant()));
        return jobId;
    }

    @Transactional
    public String enqueueDelete(AgentMemory memory) {
        String jobId = UUID.randomUUID().toString();
        repository.save(AgentMemoryIndexJobEntity.delete(jobId, memory, clock.instant()));
        return jobId;
    }

    public void process(String jobId) {
        JobSnapshot job = transactionTemplate.execute(status -> repository.findById(jobId)
                .filter(entity -> entity.getStatus() == AgentMemoryIndexJobStatus.PENDING)
                .map(JobSnapshot::from)
                .orElse(null));
        if (job == null) {
            return;
        }

        try {
            if (job.operation() == AgentMemoryIndexOperation.UPSERT) {
                indexClient.upsert(job.memory());
            } else {
                indexClient.delete(job.userId(), job.memoryId());
            }
            transactionTemplate.executeWithoutResult(status -> repository.findById(jobId)
                    .filter(entity -> entity.getStatus() == AgentMemoryIndexJobStatus.PENDING)
                    .ifPresent(entity -> entity.complete(clock.instant())));
        } catch (AgentMemoryIndexUnavailableException exception) {
            transactionTemplate.executeWithoutResult(status -> repository.findById(jobId)
                    .filter(entity -> entity.getStatus() == AgentMemoryIndexJobStatus.PENDING)
                    .ifPresent(entity -> {
                        Instant now = clock.instant();
                        entity.retry(
                                exception.getMessage(),
                                now.plus(retryDelay(entity.getAttempts() + 1)),
                                now
                        );
                    }));
            log.warn("Memory index job {} failed and will retry: {}", jobId, exception.getMessage());
        }
    }

    public MemoryIndexSearchResponse search(String userId, String query, int limit) {
        return indexClient.search(userId, query, limit);
    }

    @Scheduled(
            fixedDelayString = "${xixi.memory.index-retry-delay-ms:5000}",
            initialDelayString = "${xixi.memory.index-retry-initial-delay-ms:5000}"
    )
    public void retryPending() {
        List<String> jobIds = transactionTemplate.execute(status -> repository
                .findTop20ByStatusAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(
                        AgentMemoryIndexJobStatus.PENDING,
                        clock.instant()
                )
                .stream()
                .map(AgentMemoryIndexJobEntity::getJobId)
                .toList());
        if (jobIds != null) {
            jobIds.forEach(this::process);
        }
    }

    private Duration retryDelay(int attempts) {
        long multiplier = 1L << Math.min(Math.max(attempts - 1, 0), 6);
        Duration delay = BASE_RETRY_DELAY.multipliedBy(multiplier);
        return delay.compareTo(MAX_RETRY_DELAY) > 0 ? MAX_RETRY_DELAY : delay;
    }

    private record JobSnapshot(
            AgentMemoryIndexOperation operation,
            String memoryId,
            String userId,
            AgentMemory memory
    ) {
        static JobSnapshot from(AgentMemoryIndexJobEntity entity) {
            return new JobSnapshot(
                    entity.getOperation(),
                    entity.getMemoryId(),
                    entity.getUserId(),
                    entity.getOperation() == AgentMemoryIndexOperation.UPSERT
                            ? entity.toMemory()
                            : null
            );
        }
    }
}
