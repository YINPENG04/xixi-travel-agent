package cn.xixitravel.ride.memory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
public class AgentMemoryService {
    private static final Logger log = LoggerFactory.getLogger(AgentMemoryService.class);
    private static final Pattern KEY_PATTERN = Pattern.compile("[a-zA-Z0-9_.-]{1,64}");
    private static final int MAX_USER_ID_LENGTH = 128;
    private static final int MAX_VALUE_LENGTH = 1000;
    private static final int INDEX_CANDIDATE_LIMIT = 5;
    private static final int FINAL_MEMORY_LIMIT = 3;

    private final AgentMemoryRepository repository;
    private final AgentMemoryCache cache;
    private final AgentMemoryIndexJobService indexJobService;
    private final Clock clock;

    public AgentMemoryService(
            AgentMemoryRepository repository,
            AgentMemoryCache cache,
            AgentMemoryIndexJobService indexJobService,
            Clock clock
    ) {
        this.repository = repository;
        this.cache = cache;
        this.indexJobService = indexJobService;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<AgentMemory> list(String userId) {
        String normalizedUserId = validateUserId(userId);
        return cache.get(normalizedUserId).orElseGet(() -> {
            List<AgentMemory> memories = repository
                    .findByUserIdOrderByCategoryAscMemoryKeyAsc(normalizedUserId)
                    .stream()
                    .map(AgentMemoryEntity::toView)
                    .toList();
            cache.put(normalizedUserId, memories);
            return memories;
        });
    }

    @Transactional
    public AgentMemory remember(
            String userId,
            AgentMemoryCategory category,
            String key,
            String value,
            boolean confirmedByUser
    ) {
        requireConfirmation(confirmedByUser);
        String normalizedUserId = validateUserId(userId);
        String normalizedKey = validateKey(key);
        String normalizedValue = validateValue(value);
        if (category == null) {
            throw new IllegalArgumentException("memory category is required");
        }

        AgentMemoryEntity entity = repository
                .findByUserIdAndCategoryAndMemoryKey(normalizedUserId, category, normalizedKey)
                .map(existing -> {
                    existing.updateValue(normalizedValue, clock.instant());
                    return existing;
                })
                .orElseGet(() -> new AgentMemoryEntity(
                        UUID.randomUUID().toString(),
                        normalizedUserId,
                        category,
                        normalizedKey,
                        normalizedValue,
                        clock.instant()
                ));
        AgentMemory saved = repository.saveAndFlush(entity).toView();
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
                .map(entity -> {
                    AgentMemory deleted = entity.toView();
                    repository.delete(entity);
                    String indexJobId = indexJobService.enqueueDelete(deleted);
                    cache.evictAfterCommit(normalizedUserId);
                    afterCommit(() -> indexJobService.process(indexJobId));
                    return true;
                })
                .orElse(false);
    }

    @Transactional(readOnly = true)
    public AgentMemorySearchResponse search(String userId, String query) {
        String normalizedUserId = validateUserId(userId);
        String normalizedQuery = validateQuery(query);
        long cacheVersion = cache.currentVersion(normalizedUserId);
        String queryHash = sha256(normalizedQuery);
        var cached = cache.getSearch(normalizedUserId, cacheVersion, queryHash);
        if (cached.isPresent()) {
            return new AgentMemorySearchResponse(normalizedQuery, true, true, cached.get());
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
                .map(AgentMemoryEntity::toView)
                .filter(memory -> {
                    IndexedMemoryHit hit = hitsById.get(memory.memoryId());
                    return hit != null && hit.memoryVersion() == memory.version();
                })
                .map(memory -> new RelevantAgentMemory(
                        memory,
                        hitsById.get(memory.memoryId()).score()
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
