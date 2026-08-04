package cn.xixitravel.ride.context;

import cn.xixitravel.ride.cache.RideCacheProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Clock;

@Service
public class SessionContextService {
    private static final Logger log = LoggerFactory.getLogger(SessionContextService.class);
    private static final String KEY_PREFIX = "xixi:session-context:";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final RideCacheProperties properties;
    private final Clock clock;

    public SessionContextService(
            StringRedisTemplate redisTemplate,
            ObjectMapper objectMapper,
            RideCacheProperties properties,
            Clock clock
    ) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.clock = clock;
    }

    public SessionContextLookup get(String userId, String conversationId) {
        String normalizedUserId = required(userId, "userId", 128);
        String normalizedConversationId = required(conversationId, "conversationId", 128);
        if (!properties.isEnabled()) {
            return new SessionContextLookup(false, null);
        }
        try {
            String json = redisTemplate.opsForValue().get(key(normalizedUserId, normalizedConversationId));
            if (json == null) {
                return new SessionContextLookup(false, null);
            }
            return new SessionContextLookup(
                    true,
                    objectMapper.readValue(json, SessionContextState.class)
            );
        } catch (RuntimeException | JsonProcessingException exception) {
            log.warn("Redis session context read failed for conversation {}: {}", conversationId, exception.getMessage());
            return new SessionContextLookup(false, null);
        }
    }

    public SessionContextState save(
            String userId,
            String conversationId,
            String activeQuoteId,
            String activeOrderId,
            SessionPendingAction pendingAction,
            String taskSummary,
            long summarizedThrough
    ) {
        String normalizedUserId = required(userId, "userId", 128);
        String normalizedConversationId = required(conversationId, "conversationId", 128);
        String normalizedQuoteId = optional(activeQuoteId, "activeQuoteId", 64);
        String normalizedOrderId = optional(activeOrderId, "activeOrderId", 64);
        String normalizedSummary = optional(taskSummary, "taskSummary", 2000);
        if (summarizedThrough < 0) {
            throw new IllegalArgumentException("summarizedThrough must not be negative");
        }
        SessionContextState state = new SessionContextState(
                normalizedUserId,
                normalizedConversationId,
                normalizedQuoteId,
                normalizedOrderId,
                pendingAction == null ? SessionPendingAction.NONE : pendingAction,
                normalizedSummary,
                summarizedThrough,
                clock.instant()
        );
        if (!properties.isEnabled()) {
            throw new IllegalStateException("Redis short-term context is disabled");
        }
        try {
            redisTemplate.opsForValue().set(
                    key(normalizedUserId, normalizedConversationId),
                    objectMapper.writeValueAsString(state),
                    properties.getSessionContextTtl()
            );
            return state;
        } catch (RuntimeException | JsonProcessingException exception) {
            throw new IllegalStateException("Redis short-term context is unavailable", exception);
        }
    }

    private String required(String value, String field, int maxLength) {
        if (value == null || value.isBlank() || value.length() > maxLength) {
            throw new IllegalArgumentException(field + " must contain 1 to " + maxLength + " characters");
        }
        return value.trim();
    }

    private String optional(String value, String field, int maxLength) {
        if (value == null || value.isBlank()) {
            return null;
        }
        if (value.length() > maxLength) {
            throw new IllegalArgumentException(field + " must not exceed " + maxLength + " characters");
        }
        return value.trim();
    }

    private String key(String userId, String conversationId) {
        return KEY_PREFIX + userId + ":" + conversationId;
    }
}
