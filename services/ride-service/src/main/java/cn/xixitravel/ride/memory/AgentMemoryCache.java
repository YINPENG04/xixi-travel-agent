package cn.xixitravel.ride.memory;

import cn.xixitravel.ride.cache.RideCacheProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.Optional;

@Service
public class AgentMemoryCache {
    private static final Logger log = LoggerFactory.getLogger(AgentMemoryCache.class);
    private static final String KEY_PREFIX = "xixi:agent-memory:";
    private static final String VERSION_KEY_PREFIX = "xixi:agent-memory-version:";
    private static final String SEARCH_KEY_PREFIX = "xixi:agent-memory-search:";
    private static final TypeReference<List<AgentMemory>> MEMORY_LIST_TYPE = new TypeReference<>() {
    };
    private static final TypeReference<List<RelevantAgentMemory>> RELEVANT_MEMORY_LIST_TYPE =
            new TypeReference<>() {
            };

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final RideCacheProperties properties;

    public AgentMemoryCache(
            StringRedisTemplate redisTemplate,
            ObjectMapper objectMapper,
            RideCacheProperties properties
    ) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    public Optional<List<AgentMemory>> get(String userId) {
        if (!properties.isEnabled()) {
            return Optional.empty();
        }
        try {
            String json = redisTemplate.opsForValue().get(key(userId));
            if (json == null) {
                return Optional.empty();
            }
            return Optional.of(objectMapper.readValue(json, MEMORY_LIST_TYPE));
        } catch (RuntimeException | JsonProcessingException exception) {
            log.warn("Redis agent memory read failed for user {}: {}", userId, exception.getMessage());
            return Optional.empty();
        }
    }

    public void put(String userId, List<AgentMemory> memories) {
        if (!properties.isEnabled()) {
            return;
        }
        try {
            redisTemplate.opsForValue().set(
                    key(userId),
                    objectMapper.writeValueAsString(memories),
                    properties.getMemoryTtl()
            );
        } catch (RuntimeException | JsonProcessingException exception) {
            log.warn("Redis agent memory write failed for user {}: {}", userId, exception.getMessage());
        }
    }

    public long currentVersion(String userId) {
        if (!properties.isEnabled()) {
            return 0;
        }
        try {
            String value = redisTemplate.opsForValue().get(versionKey(userId));
            return value == null ? 0 : Long.parseLong(value);
        } catch (RuntimeException exception) {
            log.warn("Redis agent memory version read failed for user {}: {}", userId, exception.getMessage());
            return 0;
        }
    }

    public Optional<List<RelevantAgentMemory>> getSearch(
            String userId,
            long version,
            String queryHash
    ) {
        if (!properties.isEnabled()) {
            return Optional.empty();
        }
        try {
            String json = redisTemplate.opsForValue().get(searchKey(userId, version, queryHash));
            if (json == null) {
                return Optional.empty();
            }
            return Optional.of(objectMapper.readValue(json, RELEVANT_MEMORY_LIST_TYPE));
        } catch (RuntimeException | JsonProcessingException exception) {
            log.warn("Redis agent memory search read failed for user {}: {}", userId, exception.getMessage());
            return Optional.empty();
        }
    }

    public void putSearch(
            String userId,
            long version,
            String queryHash,
            List<RelevantAgentMemory> memories
    ) {
        if (!properties.isEnabled()) {
            return;
        }
        try {
            redisTemplate.opsForValue().set(
                    searchKey(userId, version, queryHash),
                    objectMapper.writeValueAsString(memories),
                    properties.getMemorySearchTtl()
            );
        } catch (RuntimeException | JsonProcessingException exception) {
            log.warn("Redis agent memory search write failed for user {}: {}", userId, exception.getMessage());
        }
    }

    public void evictAfterCommit(String userId) {
        if (!properties.isEnabled()) {
            return;
        }
        Runnable action = () -> {
            try {
                redisTemplate.delete(key(userId));
                redisTemplate.opsForValue().increment(versionKey(userId));
            } catch (RuntimeException exception) {
                log.warn("Redis agent memory eviction failed for user {}: {}", userId, exception.getMessage());
            }
        };
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

    private String key(String userId) {
        return KEY_PREFIX + userId;
    }

    private String versionKey(String userId) {
        return VERSION_KEY_PREFIX + userId;
    }

    private String searchKey(String userId, long version, String queryHash) {
        return SEARCH_KEY_PREFIX + userId + ":" + version + ":" + queryHash;
    }
}
