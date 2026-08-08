package cn.xixitravel.ride.memory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
public class AgentMemoryService {
    private static final Logger log = LoggerFactory.getLogger(AgentMemoryService.class);
    private static final Pattern KEY_PATTERN = Pattern.compile("[a-zA-Z0-9_.-]{1,64}");
    private static final Pattern VALUE_SEPARATOR = Pattern.compile("[，,；;、\\n]+");
    private static final int MAX_USER_ID_LENGTH = 128;
    private static final int MAX_VALUE_LENGTH = 1000;
    private static final int MAX_RETENTION_DAYS = 3650;
    private static final int INDEX_CANDIDATE_LIMIT = 5;
    private static final int FINAL_MEMORY_LIMIT = 3;
    private static final double MIN_RECALL_CONFIDENCE = 0.50;

    private final AgentMemoryRepository repository;
    private final AgentMemoryAuditRepository auditRepository;
    private final AgentMemoryCache cache;
    private final AgentMemoryIndexJobService indexJobService;
    private final MemorySensitiveDataPolicy sensitiveDataPolicy;
    private final Clock clock;

    public AgentMemoryService(
            AgentMemoryRepository repository,
            AgentMemoryAuditRepository auditRepository,
            AgentMemoryCache cache,
            AgentMemoryIndexJobService indexJobService,
            MemorySensitiveDataPolicy sensitiveDataPolicy,
            Clock clock
    ) {
        this.repository = repository;
        this.auditRepository = auditRepository;
        this.cache = cache;
        this.indexJobService = indexJobService;
        this.sensitiveDataPolicy = sensitiveDataPolicy;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<AgentMemory> list(String userId) {
        String normalizedUserId = validateUserId(userId);
        Instant now = clock.instant();
        var cached = cache.get(normalizedUserId);
        if (cached.isPresent()) {
            return cached.get().stream()
                    .filter(memory -> isActiveAt(memory, now))
                    .toList();
        }
        List<AgentMemory> memories = repository
                .findByUserIdOrderByCategoryAscMemoryKeyAsc(normalizedUserId)
                .stream()
                .filter(entity -> entity.isActiveAt(now))
                .map(AgentMemoryEntity::toView)
                .toList();
        cache.put(normalizedUserId, memories);
        return memories;
    }

    @Transactional
    public AgentMemory remember(
            String userId,
            AgentMemoryCategory category,
            String key,
            String value,
            boolean confirmedByUser
    ) {
        return remember(
                userId,
                category,
                key,
                value,
                confirmedByUser,
                1.0,
                null,
                AgentMemoryConflictResolution.REPLACE
        );
    }

    @Transactional
    public AgentMemory remember(
            String userId,
            AgentMemoryCategory category,
            String key,
            String value,
            boolean confirmedByUser,
            Double confidence,
            Integer retentionDays,
            AgentMemoryConflictResolution conflictResolution
    ) {
        requireConfirmation(confirmedByUser);
        String normalizedUserId = validateUserId(userId);
        String normalizedKey = validateKey(key);
        String normalizedValue = validateValue(value);
        sensitiveDataPolicy.validate(normalizedValue);
        if (category == null) {
            throw new IllegalArgumentException("memory category is required");
        }
        double normalizedConfidence = validateConfidence(confidence);
        Instant now = clock.instant();
        Instant expiresAt = expiration(category, retentionDays, now);

        AgentMemoryEntity existing = repository
                .findByUserIdAndCategoryAndMemoryKey(normalizedUserId, category, normalizedKey)
                .orElse(null);
        AgentMemoryAuditAction action = null;
        String reason = null;
        String previousValue = existing == null ? null : existing.getMemoryValue();
        Double previousConfidence = existing == null ? null : existing.getConfidence();
        Instant previousExpiresAt = existing == null ? null : existing.getExpiresAt();

        AgentMemoryEntity entity;
        if (existing == null) {
            entity = new AgentMemoryEntity(
                    UUID.randomUUID().toString(),
                    normalizedUserId,
                    category,
                    normalizedKey,
                    normalizedValue,
                    now,
                    normalizedConfidence,
                    expiresAt
            );
            action = AgentMemoryAuditAction.CREATED;
            reason = "USER_CONFIRMED_NEW_MEMORY";
        } else if (existing.getStatus() != AgentMemoryStatus.ACTIVE
                || !existing.isActiveAt(now)) {
            entity = existing;
            entity.update(normalizedValue, normalizedConfidence, expiresAt, now);
            action = AgentMemoryAuditAction.REACTIVATED;
            reason = "USER_CONFIRMED_REACTIVATION";
        } else if (existing.getMemoryValue().equals(normalizedValue)) {
            entity = existing;
            entity.refreshMetadata(normalizedConfidence, expiresAt, now);
            action = AgentMemoryAuditAction.DUPLICATE_CONFIRMED;
            reason = "USER_RECONFIRMED_EXISTING_MEMORY";
        } else {
            if (conflictResolution == null) {
                throw new IllegalStateException(
                        "memory conflict for key " + normalizedKey
                                + "; call travelMemoryList, show the existing and proposed values to the user, then retry with KEEP_EXISTING, REPLACE or MERGE"
                );
            }
            entity = existing;
            switch (conflictResolution) {
                case KEEP_EXISTING -> {
                    audit(
                            entity,
                            AgentMemoryAuditAction.CONFLICT_KEPT,
                            previousValue,
                            previousValue,
                            previousConfidence,
                            previousConfidence,
                            previousExpiresAt,
                            previousExpiresAt,
                            "USER",
                            "USER_KEPT_EXISTING_VALUE",
                            now
                    );
                    return entity.toView();
                }
                case REPLACE -> {
                    entity.update(normalizedValue, normalizedConfidence, expiresAt, now);
                    action = AgentMemoryAuditAction.REPLACED;
                    reason = "USER_CONFIRMED_REPLACEMENT";
                }
                case MERGE -> {
                    String merged = mergeValues(existing.getMemoryValue(), normalizedValue);
                    entity.update(
                            merged,
                            Math.max(existing.getConfidence(), normalizedConfidence),
                            later(existing.getExpiresAt(), expiresAt),
                            now
                    );
                    action = AgentMemoryAuditAction.MERGED;
                    reason = "USER_CONFIRMED_MERGE";
                }
            }
        }

        AgentMemory saved = repository.saveAndFlush(entity).toView();
        audit(
                entity,
                Objects.requireNonNull(action),
                previousValue,
                saved.value(),
                previousConfidence,
                saved.confidence(),
                previousExpiresAt,
                saved.expiresAt(),
                "USER",
                Objects.requireNonNull(reason),
                now
        );
        String indexJobId = indexJobService.enqueueUpsert(saved);
        cache.evictAfterCommit(normalizedUserId);
        afterCommit(() -> indexJobService.process(indexJobId));
        return saved;
    }

    @Transactional
    public boolean forget(
            String userId,
            AgentMemoryCategory category,
            String key,
            boolean confirmedByUser
    ) {
        requireConfirmation(confirmedByUser);
        String normalizedUserId = validateUserId(userId);
        String normalizedKey = validateKey(key);
        if (category == null) {
            throw new IllegalArgumentException("memory category is required");
        }

        return repository.findByUserIdAndCategoryAndMemoryKey(normalizedUserId, category, normalizedKey)
                .filter(entity -> entity.getStatus() != AgentMemoryStatus.DELETED)
                .map(entity -> {
                    Instant now = clock.instant();
                    String previousValue = entity.getMemoryValue();
                    double previousConfidence = entity.getConfidence();
                    Instant previousExpiresAt = entity.getExpiresAt();
                    entity.delete(now);
                    repository.saveAndFlush(entity);
                    audit(
                            entity,
                            AgentMemoryAuditAction.FORGOTTEN,
                            previousValue,
                            null,
                            previousConfidence,
                            null,
                            previousExpiresAt,
                            null,
                            "USER",
                            "USER_CONFIRMED_FORGET",
                            now
                    );
                    String indexJobId = indexJobService.enqueueDelete(entity.toView());
                    cache.evictAfterCommit(normalizedUserId);
                    afterCommit(() -> indexJobService.process(indexJobId));
                    return true;
                })
                .orElse(false);
    }

    @Transactional(readOnly = true)
    public List<AgentMemoryAudit> auditTrail(String userId) {
        String normalizedUserId = validateUserId(userId);
        return auditRepository.findTop100ByUserIdOrderByOccurredAtDesc(normalizedUserId)
                .stream()
                .map(AgentMemoryAuditEntity::toView)
                .toList();
    }

    @Transactional(readOnly = true)
    public AgentMemorySearchResponse search(String userId, String query) {
        String normalizedUserId = validateUserId(userId);
        String normalizedQuery = validateQuery(query);
        Instant now = clock.instant();
        long cacheVersion = cache.currentVersion(normalizedUserId);
        String queryHash = sha256(normalizedQuery);
        var cached = cache.getSearch(normalizedUserId, cacheVersion, queryHash);
        if (cached.isPresent()) {
            List<RelevantAgentMemory> validCached = cached.get().stream()
                    .filter(result -> isActiveAt(result.memory(), now))
                    .filter(result -> result.memory().confidence() >= MIN_RECALL_CONFIDENCE)
                    .toList();
            return new AgentMemorySearchResponse(normalizedQuery, true, true, validCached);
        }

        final MemoryIndexSearchResponse indexed;
        try {
            indexed = indexJobService.search(
                    normalizedUserId,
                    normalizedQuery,
                    INDEX_CANDIDATE_LIMIT
            );
        } catch (AgentMemoryIndexUnavailableException exception) {
            log.warn("Semantic memory search unavailable for user {}: {}", normalizedUserId, exception.getMessage());
            return new AgentMemorySearchResponse(normalizedQuery, false, false, List.of());
        }

        Map<String, IndexedMemoryHit> hitsById = new HashMap<>();
        for (IndexedMemoryHit hit : indexed.hits()) {
            hitsById.put(hit.memoryId(), hit);
        }
        List<RelevantAgentMemory> verified = repository
                .findByUserIdAndMemoryIdIn(normalizedUserId, hitsById.keySet())
                .stream()
                .filter(entity -> entity.isActiveAt(now))
                .map(AgentMemoryEntity::toView)
                .filter(memory -> memory.confidence() >= MIN_RECALL_CONFIDENCE)
                .filter(memory -> {
                    IndexedMemoryHit hit = hitsById.get(memory.memoryId());
                    return hit != null && hit.memoryVersion() == memory.version();
                })
                .map(memory -> new RelevantAgentMemory(
                        memory,
                        hitsById.get(memory.memoryId()).score() * memory.confidence()
                ))
                .sorted(Comparator
                        .comparingDouble(RelevantAgentMemory::score)
                        .reversed()
                        .thenComparing(
                                result -> result.memory().updatedAt(),
                                Comparator.reverseOrder()
                        ))
                .limit(FINAL_MEMORY_LIMIT)
                .toList();
        cache.putSearch(normalizedUserId, cacheVersion, queryHash, verified);
        return new AgentMemorySearchResponse(normalizedQuery, true, false, verified);
    }

    @Scheduled(fixedDelay = 3_600_000, initialDelay = 60_000)
    @Transactional
    public void expireDueMemories() {
        Instant now = clock.instant();
        repository.findTop100ByStatusAndExpiresAtLessThanEqualOrderByExpiresAtAsc(
                        AgentMemoryStatus.ACTIVE,
                        now
                )
                .forEach(entity -> {
                    String previousValue = entity.getMemoryValue();
                    double previousConfidence = entity.getConfidence();
                    Instant previousExpiresAt = entity.getExpiresAt();
                    entity.expire(now);
                    audit(
                            entity,
                            AgentMemoryAuditAction.EXPIRED,
                            previousValue,
                            null,
                            previousConfidence,
                            null,
                            previousExpiresAt,
                            null,
                            "SYSTEM",
                            "RETENTION_PERIOD_ELAPSED",
                            now
                    );
                    String indexJobId = indexJobService.enqueueDelete(entity.toView());
                    cache.evictAfterCommit(entity.getUserId());
                    afterCommit(() -> indexJobService.process(indexJobId));
                });
    }

    private void audit(
            AgentMemoryEntity memory,
            AgentMemoryAuditAction action,
            String previousValue,
            String newValue,
            Double previousConfidence,
            Double newConfidence,
            Instant previousExpiresAt,
            Instant newExpiresAt,
            String actor,
            String reason,
            Instant now
    ) {
        auditRepository.save(new AgentMemoryAuditEntity(
                UUID.randomUUID().toString(),
                memory,
                action,
                hashOrNull(previousValue),
                hashOrNull(newValue),
                previousConfidence,
                newConfidence,
                previousExpiresAt,
                newExpiresAt,
                actor,
                reason,
                now
        ));
    }

    private String mergeValues(String existing, String proposed) {
        LinkedHashSet<String> parts = new LinkedHashSet<>();
        parts.addAll(Arrays.stream(VALUE_SEPARATOR.split(existing))
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .toList());
        parts.addAll(Arrays.stream(VALUE_SEPARATOR.split(proposed))
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .toList());
        String merged = String.join("；", parts);
        if (merged.length() > MAX_VALUE_LENGTH) {
            throw new IllegalArgumentException("merged memory value must not exceed 1000 characters");
        }
        return merged;
    }

    private Instant expiration(AgentMemoryCategory category, Integer retentionDays, Instant now) {
        int days;
        if (retentionDays == null) {
            days = switch (category) {
                case COMMON_PLACE -> 180;
                case PREFERENCE, ACCESSIBILITY, INVOICE_PREFERENCE -> 365;
            };
        } else {
            if (retentionDays < 0 || retentionDays > MAX_RETENTION_DAYS) {
                throw new IllegalArgumentException("retentionDays must be between 0 and 3650");
            }
            if (retentionDays == 0) {
                return null;
            }
            days = retentionDays;
        }
        return now.plus(Duration.ofDays(days));
    }

    private double validateConfidence(Double confidence) {
        double normalized = confidence == null ? 1.0 : confidence;
        if (!Double.isFinite(normalized) || normalized < 0.0 || normalized > 1.0) {
            throw new IllegalArgumentException("confidence must be between 0 and 1");
        }
        return normalized;
    }

    private Instant later(Instant first, Instant second) {
        if (first == null || second == null) {
            return null;
        }
        return first.isAfter(second) ? first : second;
    }

    private boolean isActiveAt(AgentMemory memory, Instant now) {
        return memory.status() == AgentMemoryStatus.ACTIVE
                && (memory.expiresAt() == null || memory.expiresAt().isAfter(now));
    }

    private void requireConfirmation(boolean confirmedByUser) {
        if (!confirmedByUser) {
            throw new IllegalStateException("explicit user confirmation is required to change long-term memory");
        }
    }

    private String validateUserId(String userId) {
        if (userId == null || userId.isBlank() || userId.length() > MAX_USER_ID_LENGTH) {
            throw new IllegalArgumentException("userId must contain 1 to 128 characters");
        }
        return userId.trim();
    }

    private String validateKey(String key) {
        if (key == null || !KEY_PATTERN.matcher(key.trim()).matches()) {
            throw new IllegalArgumentException("memory key must contain only letters, numbers, dot, dash or underscore");
        }
        return key.trim();
    }

    private String validateValue(String value) {
        if (value == null || value.isBlank() || value.length() > MAX_VALUE_LENGTH) {
            throw new IllegalArgumentException("memory value must contain 1 to 1000 characters");
        }
        return value.trim();
    }

    private String validateQuery(String query) {
        if (query == null || query.isBlank() || query.length() > 500) {
            throw new IllegalArgumentException("memory search query must contain 1 to 500 characters");
        }
        return query.trim();
    }

    private String hashOrNull(String value) {
        return value == null ? null : sha256(value);
    }

    private String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private void afterCommit(Runnable action) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.isSynchronizationActive()) {
            action.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                action.run();
            }
        });
    }
}
