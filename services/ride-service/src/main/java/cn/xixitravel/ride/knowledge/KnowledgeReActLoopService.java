package cn.xixitravel.ride.knowledge;

import cn.xixitravel.ride.cache.RideCacheProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
public class KnowledgeReActLoopService {
    private static final Logger log = LoggerFactory.getLogger(KnowledgeReActLoopService.class);
    private static final String KEY_PREFIX = "xixi:react-rag:";
    private static final String LOCK_SUFFIX = ":lock";
    private static final int MAX_ITERATIONS = 2;
    private static final Set<String> RETRYABLE_STATUSES = Set.of(
            "EMPTY", "LOW_SCORE", "AMBIGUOUS"
    );

    private final KnowledgeSearchService searchService;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final RideCacheProperties cacheProperties;
    private final Clock clock;

    public KnowledgeReActLoopService(
            KnowledgeSearchService searchService,
            StringRedisTemplate redisTemplate,
            ObjectMapper objectMapper,
            RideCacheProperties cacheProperties,
            Clock clock
    ) {
        this.searchService = searchService;
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.cacheProperties = cacheProperties;
        this.clock = clock;
    }

    public KnowledgeReActObservation search(
            String userId,
            String conversationId,
            String query,
            String cycleId
    ) {
        String normalizedUserId = required(userId, "userId", 128);
        String normalizedConversationId = required(conversationId, "conversationId", 128);
        String normalizedQuery = required(query, "query", 500);
        if (cycleId == null || cycleId.isBlank()) {
            return start(normalizedUserId, normalizedConversationId, normalizedQuery);
        }
        return retry(
                normalizedUserId,
                normalizedConversationId,
                normalizedQuery,
                validCycleId(cycleId)
        );
    }

    private KnowledgeReActObservation start(
            String userId,
            String conversationId,
            String query
    ) {
        String cycleId = UUID.randomUUID().toString();
        KnowledgeSearchResponse response = searchService.search(query, 3, null);
        if (!isRetryable(response.retrievalStatus())) {
            return observation(cycleId, 1, true, "EVIDENCE_READY", response);
        }

        KnowledgeReActState state = new KnowledgeReActState(
                cycleId,
                userId,
                conversationId,
                query,
                1,
                false,
                clock.instant()
        );
        if (!save(state)) {
            return observation(
                    cycleId,
                    1,
                    true,
                    "STATE_STORE_UNAVAILABLE",
                    response,
                    "STOP_AND_REPORT_NO_EVIDENCE"
            );
        }
        return observation(
                cycleId,
                1,
                false,
                null,
                response,
                "REFORMULATE_QUERY_AND_RETRY_WITH_CYCLE_ID"
        );
    }

    private KnowledgeReActObservation retry(
            String userId,
            String conversationId,
            String query,
            String cycleId
    ) {
        KnowledgeReActState state = read(userId, conversationId, cycleId);
        if (state == null) {
            return rejected(cycleId, query, "CYCLE_NOT_FOUND_OR_EXPIRED");
        }
        if (state.terminal() || state.iteration() >= MAX_ITERATIONS) {
            return rejected(cycleId, query, "CYCLE_ALREADY_FINISHED");
        }
        if (state.lastQuery().equalsIgnoreCase(query)) {
            save(new KnowledgeReActState(
                    cycleId,
                    userId,
                    conversationId,
                    query,
                    MAX_ITERATIONS,
                    true,
                    clock.instant()
            ));
            return rejected(cycleId, query, "QUERY_NOT_REFORMULATED");
        }

        String stateKey = key(userId, conversationId, cycleId);
        if (!acquireRetryLock(stateKey)) {
            return rejected(cycleId, query, "RETRY_ALREADY_IN_PROGRESS");
        }
        try {
            KnowledgeReActState claimed = new KnowledgeReActState(
                    cycleId,
                    userId,
                    conversationId,
                    query,
                    MAX_ITERATIONS,
                    true,
                    clock.instant()
            );
            if (!save(claimed)) {
                return rejected(cycleId, query, "STATE_STORE_UNAVAILABLE");
            }

            KnowledgeSearchResponse response = searchService.search(query, 3, null);
            String stopReason = isRetryable(response.retrievalStatus())
                    ? "RETRY_LIMIT_REACHED"
                    : "EVIDENCE_READY";
            String nextAction = isRetryable(response.retrievalStatus())
                    ? "STOP_AND_REPORT_NO_EVIDENCE"
                    : response.recommendedNextAction();
            return observation(
                    cycleId,
                    MAX_ITERATIONS,
                    true,
                    stopReason,
                    response,
                    nextAction
            );
        } finally {
            releaseRetryLock(stateKey);
        }
    }

    private KnowledgeReActObservation observation(
            String cycleId,
            int iteration,
            boolean terminal,
            String stopReason,
            KnowledgeSearchResponse response
    ) {
        return observation(
                cycleId,
                iteration,
                terminal,
                stopReason,
                response,
                response.recommendedNextAction()
        );
    }

    private KnowledgeReActObservation observation(
            String cycleId,
            int iteration,
            boolean terminal,
            String stopReason,
            KnowledgeSearchResponse response,
            String nextAction
    ) {
        return new KnowledgeReActObservation(
                cycleId,
                iteration,
                MAX_ITERATIONS,
                terminal,
                stopReason,
                response.query(),
                response.collection(),
                response.retrievalStatus(),
                nextAction,
                response.topScore(),
                response.scoreGap(),
                response.observationReason(),
                response.hits()
        );
    }

    private KnowledgeReActObservation rejected(
            String cycleId,
            String query,
            String stopReason
    ) {
        return new KnowledgeReActObservation(
                cycleId,
                MAX_ITERATIONS,
                MAX_ITERATIONS,
                true,
                stopReason,
                query,
                "xixi_travel_knowledge",
                "RETRY_REJECTED",
                "STOP_AND_REPORT",
                0.0,
                0.0,
                "检索循环已终止，不能继续调用该 cycle",
                List.of()
        );
    }

    private boolean isRetryable(String status) {
        return RETRYABLE_STATUSES.contains(status);
    }

    private KnowledgeReActState read(
            String userId,
            String conversationId,
            String cycleId
    ) {
        if (!cacheProperties.isEnabled()) {
            return null;
        }
        try {
            String json = redisTemplate.opsForValue().get(key(userId, conversationId, cycleId));
            return json == null ? null : objectMapper.readValue(json, KnowledgeReActState.class);
        } catch (RuntimeException | JsonProcessingException exception) {
            log.warn("Redis ReAct state read failed for cycle {}: {}", cycleId, exception.getMessage());
            return null;
        }
    }

    private boolean save(KnowledgeReActState state) {
        if (!cacheProperties.isEnabled()) {
            return false;
        }
        try {
            redisTemplate.opsForValue().set(
                    key(state.userId(), state.conversationId(), state.cycleId()),
                    objectMapper.writeValueAsString(state),
                    cacheProperties.getReactRagTtl()
            );
            return true;
        } catch (RuntimeException | JsonProcessingException exception) {
            log.warn("Redis ReAct state write failed for cycle {}: {}", state.cycleId(), exception.getMessage());
            return false;
        }
    }

    private boolean acquireRetryLock(String stateKey) {
        try {
            return Boolean.TRUE.equals(redisTemplate.opsForValue().setIfAbsent(
                    stateKey + LOCK_SUFFIX,
                    "1",
                    cacheProperties.getReactRagLockTtl()
            ));
        } catch (RuntimeException exception) {
            log.warn("Redis ReAct lock failed for key {}: {}", stateKey, exception.getMessage());
            return false;
        }
    }

    private void releaseRetryLock(String stateKey) {
        try {
            redisTemplate.delete(stateKey + LOCK_SUFFIX);
        } catch (RuntimeException exception) {
            log.warn("Redis ReAct lock release failed for key {}: {}", stateKey, exception.getMessage());
        }
    }

    private String key(String userId, String conversationId, String cycleId) {
        return KEY_PREFIX + userId + ":" + conversationId + ":" + cycleId;
    }

    private String validCycleId(String cycleId) {
        String normalized = cycleId.trim();
        if (normalized.length() > 64 || !normalized.matches("[A-Za-z0-9-]+")) {
            throw new IllegalArgumentException("cycleId format is invalid");
        }
        return normalized;
    }

    private String required(String value, String field, int maxLength) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        String normalized = value.trim();
        if (normalized.length() > maxLength) {
            throw new IllegalArgumentException(field + " must not exceed " + maxLength + " characters");
        }
        return normalized;
    }
}
