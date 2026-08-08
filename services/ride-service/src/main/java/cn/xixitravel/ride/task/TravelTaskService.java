package cn.xixitravel.ride.task;

import cn.xixitravel.ride.cache.RideCacheProperties;
import cn.xixitravel.ride.context.SessionPendingAction;
import cn.xixitravel.ride.domain.RideStatus;
import cn.xixitravel.ride.intent.IntentRecognitionResult;
import cn.xixitravel.ride.intent.TravelIntent;
import cn.xixitravel.ride.intent.TravelIntentRecognizer;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Service
public class TravelTaskService {
    private static final Logger log = LoggerFactory.getLogger(TravelTaskService.class);
    private static final String KEY_PREFIX = "xixi:travel-task:";
    private static final int MAX_FAILURES = 2;
    private static final DefaultRedisScript<Long> TASK_CAS_SCRIPT = new DefaultRedisScript<>("""
            local current = redis.call('GET', KEYS[1])
            local expectedPresent = ARGV[1]

            if expectedPresent == '0' then
                if current then
                    return 0
                end
                redis.call('SET', KEYS[1], ARGV[4], 'PX', ARGV[5])
                return 1
            end

            if not current then
                return -1
            end

            local decoded, state = pcall(cjson.decode, current)
            if not decoded or type(state) ~= 'table' then
                return -2
            end

            if state.taskId ~= ARGV[2] or tonumber(state.version) ~= tonumber(ARGV[3]) then
                return 0
            end

            redis.call('SET', KEYS[1], ARGV[4], 'PX', ARGV[5])
            return 1
            """, Long.class);

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final RideCacheProperties properties;
    private final TravelIntentRecognizer intentRecognizer;
    private final Clock clock;

    public TravelTaskService(
            StringRedisTemplate redisTemplate,
            ObjectMapper objectMapper,
            RideCacheProperties properties,
            TravelIntentRecognizer intentRecognizer,
            Clock clock
    ) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.intentRecognizer = intentRecognizer;
        this.clock = clock;
    }

    public IntentRecognitionResult recognize(
            String userId,
            String conversationId,
            String utterance,
            String origin,
            String destination,
            String orderId
    ) {
        String normalizedUserId = required(userId, "userId", 128);
        String normalizedConversationId = required(conversationId, "conversationId", 128);
        TravelTaskState current = read(normalizedUserId, normalizedConversationId);
        return intentRecognizer.recognize(
                utterance,
                current == null ? SessionPendingAction.NONE : current.pendingAction(),
                origin,
                destination,
                orderId
        );
    }

    public TravelTaskState start(
            String userId,
            String conversationId,
            String utterance,
            String origin,
            String destination,
            String orderId
    ) {
        String normalizedUserId = required(userId, "userId", 128);
        String normalizedConversationId = required(conversationId, "conversationId", 128);
        TravelTaskState current = read(normalizedUserId, normalizedConversationId);
        IntentRecognitionResult recognized = intentRecognizer.recognize(
                utterance,
                current == null ? SessionPendingAction.NONE : current.pendingAction(),
                origin,
                destination,
                orderId
        );

        if (current != null && !current.terminal()) {
            if (recognized.intent() == TravelIntent.CONFIRM_PENDING_ACTION) {
                if (current.phase() == TravelTaskPhase.WAITING_FOR_QUOTE_SELECTION) {
                    throw new IllegalStateException(
                            "a concrete quoteId must be selected with QUOTE_SELECTED before confirming an order"
                    );
                }
                TravelTaskState confirmed = transition(
                        current,
                        TravelTaskObservationType.USER_CONFIRMED,
                        null,
                        null
                );
                compareAndSet(current, confirmed);
                return confirmed;
            }
            if (recognized.intent() == TravelIntent.REJECT_PENDING_ACTION) {
                TravelTaskState rejected = transition(
                        current,
                        TravelTaskObservationType.USER_REJECTED,
                        null,
                        null
                );
                compareAndSet(current, rejected);
                return rejected;
            }
            if (current.phase() == TravelTaskPhase.COLLECTING_ROUTE
                    && recognized.extractedSlots().containsKey("origin")
                    && recognized.extractedSlots().containsKey("destination")) {
                TravelTaskState supplied = copy(
                        current,
                        current.intent(),
                        TravelTaskPhase.READY_TO_QUOTE,
                        TravelTaskAction.CALL_RIDE_QUOTE,
                        SessionPendingAction.NONE,
                        recognized.extractedSlots().get("origin"),
                        recognized.extractedSlots().get("destination"),
                        current.quoteId(),
                        current.orderId(),
                        current.lastOrderStatus(),
                        current.failureCount(),
                        false,
                        null
                );
                compareAndSet(current, supplied);
                return supplied;
            }
            if (current.phase() == TravelTaskPhase.COLLECTING_ORDER_ID
                    && recognized.extractedSlots().containsKey("orderId")) {
                TravelTaskState supplied = withOrderId(
                        current,
                        recognized.extractedSlots().get("orderId")
                );
                compareAndSet(current, supplied);
                return supplied;
            }
            if (recognized.intent() == TravelIntent.UNKNOWN || recognized.ambiguous()) {
                return current;
            }
        }

        TravelTaskState created = create(
                normalizedUserId,
                normalizedConversationId,
                recognized
        );
        compareAndSet(current, created);
        return created;
    }

    public TravelTaskLookup get(String userId, String conversationId) {
        String normalizedUserId = required(userId, "userId", 128);
        String normalizedConversationId = required(conversationId, "conversationId", 128);
        TravelTaskState state = read(normalizedUserId, normalizedConversationId);
        return new TravelTaskLookup(state != null, state);
    }

    public TravelTaskState observe(
            String userId,
            String conversationId,
            String expectedTaskId,
            long expectedVersion,
            TravelTaskObservationType observation,
            String resourceId,
            String detail
    ) {
        String normalizedUserId = required(userId, "userId", 128);
        String normalizedConversationId = required(conversationId, "conversationId", 128);
        String normalizedExpectedTaskId = required(expectedTaskId, "expectedTaskId", 128);
        if (observation == null) {
            throw new IllegalArgumentException("observation is required");
        }
        TravelTaskState current = read(normalizedUserId, normalizedConversationId);
        if (current == null) {
            throw new IllegalStateException("travel task does not exist or has expired");
        }
        if (!current.taskId().equals(normalizedExpectedTaskId) || current.version() != expectedVersion) {
            throw new IllegalStateException("travel task identity or version conflict; reload the task before retrying");
        }
        TravelTaskState next = transition(current, observation, resourceId, detail);
        compareAndSet(current, next);
        return next;
    }

    private TravelTaskState create(
            String userId,
            String conversationId,
            IntentRecognitionResult recognized
    ) {
        String origin = recognized.extractedSlots().get("origin");
        String destination = recognized.extractedSlots().get("destination");
        String orderId = recognized.extractedSlots().get("orderId");
        TravelTaskPhase phase = null;
        TravelTaskAction action = null;

        if (recognized.ambiguous() || recognized.intent() == TravelIntent.UNKNOWN) {
            phase = TravelTaskPhase.NEEDS_CLARIFICATION;
            action = TravelTaskAction.ASK_FOR_CLARIFICATION;
        } else {
            switch (recognized.intent()) {
                case RIDE_QUOTE, RIDE_CREATE -> {
                    boolean routeReady = origin != null && destination != null;
                    phase = routeReady ? TravelTaskPhase.READY_TO_QUOTE : TravelTaskPhase.COLLECTING_ROUTE;
                    action = routeReady ? TravelTaskAction.CALL_RIDE_QUOTE : TravelTaskAction.ASK_FOR_ROUTE;
                }
                case RIDE_STATUS -> {
                    phase = orderId == null
                            ? TravelTaskPhase.COLLECTING_ORDER_ID
                            : TravelTaskPhase.READY_TO_QUERY_STATUS;
                    action = orderId == null
                            ? TravelTaskAction.ASK_FOR_ORDER_ID
                            : TravelTaskAction.CALL_RIDE_STATUS;
                }
                case RIDE_CANCEL -> {
                    phase = orderId == null
                            ? TravelTaskPhase.COLLECTING_ORDER_ID
                            : TravelTaskPhase.READY_TO_PREPARE_CANCEL;
                    action = orderId == null
                            ? TravelTaskAction.ASK_FOR_ORDER_ID
                            : TravelTaskAction.CALL_RIDE_PREPARE_CANCEL;
                }
                case QUERY_NOTIFICATIONS -> {
                    phase = orderId == null
                            ? TravelTaskPhase.COLLECTING_ORDER_ID
                            : TravelTaskPhase.READY_TO_QUERY_NOTIFICATIONS;
                    action = orderId == null
                            ? TravelTaskAction.ASK_FOR_ORDER_ID
                            : TravelTaskAction.CALL_RIDE_NOTIFICATIONS;
                }
                case QUERY_INVOICE -> {
                    phase = orderId == null
                            ? TravelTaskPhase.COLLECTING_ORDER_ID
                            : TravelTaskPhase.READY_TO_QUERY_INVOICE;
                    action = orderId == null
                            ? TravelTaskAction.ASK_FOR_ORDER_ID
                            : TravelTaskAction.CALL_RIDE_INVOICE_ELIGIBILITY;
                }
                case SEARCH_KNOWLEDGE -> {
                    phase = TravelTaskPhase.READY_TO_SEARCH_KNOWLEDGE;
                    action = TravelTaskAction.CALL_TRAVEL_KNOWLEDGE_SEARCH;
                }
                case LIST_MEMORY -> {
                    phase = TravelTaskPhase.READY_TO_LIST_MEMORY;
                    action = TravelTaskAction.CALL_TRAVEL_MEMORY_LIST;
                }
                case SEARCH_MEMORY -> {
                    phase = TravelTaskPhase.READY_TO_SEARCH_MEMORY;
                    action = TravelTaskAction.CALL_TRAVEL_MEMORY_SEARCH;
                }
                case REMEMBER_PREFERENCE -> {
                    phase = TravelTaskPhase.READY_TO_REMEMBER_MEMORY;
                    action = TravelTaskAction.CALL_TRAVEL_MEMORY_REMEMBER;
                }
                case FORGET_PREFERENCE -> {
                    phase = TravelTaskPhase.READY_TO_FORGET_MEMORY;
                    action = TravelTaskAction.CALL_TRAVEL_MEMORY_FORGET;
                }
                case CONFIRM_PENDING_ACTION, REJECT_PENDING_ACTION, UNKNOWN -> {
                    phase = TravelTaskPhase.NEEDS_CLARIFICATION;
                    action = TravelTaskAction.ASK_FOR_CLARIFICATION;
                }
            }
        }

        return new TravelTaskState(
                UUID.randomUUID().toString(),
                userId,
                conversationId,
                recognized.intent(),
                Objects.requireNonNull(phase),
                Objects.requireNonNull(action),
                SessionPendingAction.NONE,
                origin,
                destination,
                null,
                orderId,
                null,
                0,
                1,
                false,
                null,
                clock.instant()
        );
    }

    private TravelTaskState transition(
            TravelTaskState current,
            TravelTaskObservationType observation,
            String resourceId,
            String detail
    ) {
        if (current.terminal()) {
            throw new IllegalStateException("travel task is already terminal");
        }
        if (observation == TravelTaskObservationType.TOOL_FAILED) {
            int failures = current.failureCount() + 1;
            if (failures >= MAX_FAILURES) {
                return copy(
                        current,
                        current.intent(),
                        TravelTaskPhase.FAILED,
                        TravelTaskAction.STOP,
                        SessionPendingAction.NONE,
                        current.origin(),
                        current.destination(),
                        current.quoteId(),
                        current.orderId(),
                        current.lastOrderStatus(),
                        failures,
                        true,
                        optional(detail, 500) == null ? "TOOL_RETRY_LIMIT_REACHED" : optional(detail, 500)
                );
            }
            return copy(
                    current,
                    current.intent(),
                    current.phase(),
                    current.nextAction(),
                    current.pendingAction(),
                    current.origin(),
                    current.destination(),
                    current.quoteId(),
                    current.orderId(),
                    current.lastOrderStatus(),
                    failures,
                    false,
                    "RETRY_TOOL_ONCE"
            );
        }

        return switch (current.phase()) {
            case COLLECTING_ROUTE -> requireObservation(
                    current,
                    observation,
                    TravelTaskObservationType.ROUTE_PROVIDED,
                    TravelTaskPhase.READY_TO_QUOTE,
                    TravelTaskAction.CALL_RIDE_QUOTE,
                    SessionPendingAction.NONE,
                    current.quoteId(),
                    current.orderId(),
                    detail
            );
            case READY_TO_QUOTE -> requireObservation(
                    current,
                    observation,
                    TravelTaskObservationType.QUOTE_RETURNED,
                    TravelTaskPhase.WAITING_FOR_QUOTE_SELECTION,
                    TravelTaskAction.ASK_FOR_QUOTE_SELECTION,
                    SessionPendingAction.WAITING_FOR_QUOTE_CONFIRMATION,
                    null,
                    current.orderId(),
                    null
            );
            case WAITING_FOR_QUOTE_SELECTION -> requireObservation(
                    current,
                    observation,
                    TravelTaskObservationType.QUOTE_SELECTED,
                    TravelTaskPhase.READY_TO_PREPARE_CREATE,
                    TravelTaskAction.CALL_RIDE_PREPARE_CREATE,
                    SessionPendingAction.NONE,
                    required(resourceId, "quoteId", 64),
                    current.orderId(),
                    null
            );
            case READY_TO_PREPARE_CREATE -> requireObservation(
                    current,
                    observation,
                    TravelTaskObservationType.CREATE_PREPARED,
                    TravelTaskPhase.WAITING_FOR_CREATE_CONFIRMATION,
                    TravelTaskAction.ASK_FOR_CREATE_CONFIRMATION,
                    SessionPendingAction.WAITING_FOR_ORDER_CONFIRMATION,
                    current.quoteId(),
                    current.orderId(),
                    null
            );
            case WAITING_FOR_CREATE_CONFIRMATION -> confirmationTransition(
                    current,
                    observation,
                    TravelTaskPhase.READY_TO_CREATE,
                    TravelTaskAction.CALL_RIDE_CREATE
            );
            case READY_TO_CREATE -> requireObservation(
                    current,
                    observation,
                    TravelTaskObservationType.ORDER_CREATED,
                    TravelTaskPhase.WAITING_FOR_DISPATCH,
                    TravelTaskAction.WAIT_OR_QUERY_DISPATCH,
                    SessionPendingAction.NONE,
                    current.quoteId(),
                    required(resourceId, "orderId", 64),
                    "CREATED"
            );
            case WAITING_FOR_DISPATCH, READY_TO_QUERY_STATUS -> orderStatusTransition(
                    current,
                    observation,
                    detail
            );
            case READY_TO_PREPARE_CANCEL -> requireObservation(
                    current,
                    observation,
                    TravelTaskObservationType.CANCEL_PREPARED,
                    TravelTaskPhase.WAITING_FOR_CANCEL_CONFIRMATION,
                    TravelTaskAction.ASK_FOR_CANCEL_CONFIRMATION,
                    SessionPendingAction.WAITING_FOR_CANCEL_CONFIRMATION,
                    current.quoteId(),
                    current.orderId(),
                    null
            );
            case WAITING_FOR_CANCEL_CONFIRMATION -> confirmationTransition(
                    current,
                    observation,
                    TravelTaskPhase.READY_TO_CANCEL,
                    TravelTaskAction.CALL_RIDE_CANCEL
            );
            case READY_TO_CANCEL -> completeOn(
                    current,
                    observation,
                    TravelTaskObservationType.ORDER_CANCELLED,
                    "ORDER_CANCELLED"
            );
            case READY_TO_QUERY_NOTIFICATIONS -> completeOn(
                    current,
                    observation,
                    TravelTaskObservationType.NOTIFICATIONS_RETURNED,
                    "NOTIFICATIONS_RETURNED"
            );
            case READY_TO_QUERY_INVOICE -> completeOn(
                    current,
                    observation,
                    TravelTaskObservationType.INVOICE_ELIGIBILITY_RETURNED,
                    "INVOICE_ELIGIBILITY_RETURNED"
            );
            case READY_TO_SEARCH_KNOWLEDGE -> completeOn(
                    current,
                    observation,
                    TravelTaskObservationType.KNOWLEDGE_RETURNED,
                    "KNOWLEDGE_RETURNED"
            );
            case READY_TO_LIST_MEMORY, READY_TO_SEARCH_MEMORY,
                    READY_TO_REMEMBER_MEMORY, READY_TO_FORGET_MEMORY -> completeOn(
                    current,
                    observation,
                    TravelTaskObservationType.MEMORY_ACTION_COMPLETED,
                    "MEMORY_ACTION_COMPLETED"
            );
            case NEEDS_CLARIFICATION, COLLECTING_ORDER_ID, COMPLETED, CANCELLED, FAILED ->
                    throw illegalTransition(current, observation);
        };
    }

    private TravelTaskState withOrderId(TravelTaskState current, String orderId) {
        TravelTaskPhase phase;
        TravelTaskAction action;
        switch (current.intent()) {
            case RIDE_STATUS -> {
                phase = TravelTaskPhase.READY_TO_QUERY_STATUS;
                action = TravelTaskAction.CALL_RIDE_STATUS;
            }
            case RIDE_CANCEL -> {
                phase = TravelTaskPhase.READY_TO_PREPARE_CANCEL;
                action = TravelTaskAction.CALL_RIDE_PREPARE_CANCEL;
            }
            case QUERY_NOTIFICATIONS -> {
                phase = TravelTaskPhase.READY_TO_QUERY_NOTIFICATIONS;
                action = TravelTaskAction.CALL_RIDE_NOTIFICATIONS;
            }
            case QUERY_INVOICE -> {
                phase = TravelTaskPhase.READY_TO_QUERY_INVOICE;
                action = TravelTaskAction.CALL_RIDE_INVOICE_ELIGIBILITY;
            }
            default -> throw new IllegalStateException("current task does not accept an order ID");
        }
        return copy(
                current,
                current.intent(),
                phase,
                action,
                SessionPendingAction.NONE,
                current.origin(),
                current.destination(),
                current.quoteId(),
                orderId,
                current.lastOrderStatus(),
                current.failureCount(),
                false,
                null
        );
    }

    private TravelTaskState confirmationTransition(
            TravelTaskState current,
            TravelTaskObservationType observation,
            TravelTaskPhase confirmedPhase,
            TravelTaskAction confirmedAction
    ) {
        if (observation == TravelTaskObservationType.USER_REJECTED) {
            return copy(
                    current,
                    current.intent(),
                    TravelTaskPhase.CANCELLED,
                    TravelTaskAction.STOP,
                    SessionPendingAction.NONE,
                    current.origin(),
                    current.destination(),
                    current.quoteId(),
                    current.orderId(),
                    current.lastOrderStatus(),
                    current.failureCount(),
                    true,
                    "USER_REJECTED"
            );
        }
        if (observation != TravelTaskObservationType.USER_CONFIRMED) {
            throw illegalTransition(current, observation);
        }
        return copy(
                current,
                current.intent(),
                confirmedPhase,
                confirmedAction,
                SessionPendingAction.NONE,
                current.origin(),
                current.destination(),
                current.quoteId(),
                current.orderId(),
                current.lastOrderStatus(),
                current.failureCount(),
                false,
                null
        );
    }

    private TravelTaskState orderStatusTransition(
            TravelTaskState current,
            TravelTaskObservationType observation,
            String detail
    ) {
        if (observation != TravelTaskObservationType.ORDER_STATUS_UPDATED) {
            throw illegalTransition(current, observation);
        }
        RideStatus status;
        try {
            status = RideStatus.valueOf(required(detail, "rideStatus", 32));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("rideStatus is invalid", exception);
        }
        if (current.phase() == TravelTaskPhase.READY_TO_QUERY_STATUS) {
            return completedWithStatus(current, status, status.name());
        }
        if (status == RideStatus.CREATED) {
            return copy(
                    current,
                    current.intent(),
                    TravelTaskPhase.WAITING_FOR_DISPATCH,
                    TravelTaskAction.WAIT_OR_QUERY_DISPATCH,
                    SessionPendingAction.NONE,
                    current.origin(),
                    current.destination(),
                    current.quoteId(),
                    current.orderId(),
                    status.name(),
                    current.failureCount(),
                    false,
                    null
            );
        }
        if (status == RideStatus.CANCELLED) {
            return copy(
                    current,
                    current.intent(),
                    TravelTaskPhase.FAILED,
                    TravelTaskAction.RESPOND_AND_STOP,
                    SessionPendingAction.NONE,
                    current.origin(),
                    current.destination(),
                    current.quoteId(),
                    current.orderId(),
                    status.name(),
                    current.failureCount(),
                    true,
                    "ORDER_CANCELLED_BEFORE_DISPATCH"
            );
        }
        return completedWithStatus(current, status, status.name());
    }

    private TravelTaskState requireObservation(
            TravelTaskState current,
            TravelTaskObservationType actual,
            TravelTaskObservationType expected,
            TravelTaskPhase phase,
            TravelTaskAction action,
            SessionPendingAction pendingAction,
            String quoteId,
            String orderId,
            String lastOrderStatus
    ) {
        if (actual != expected) {
            throw illegalTransition(current, actual);
        }
        return copy(
                current,
                current.intent(),
                phase,
                action,
                pendingAction,
                current.origin(),
                current.destination(),
                quoteId,
                orderId,
                lastOrderStatus,
                current.failureCount(),
                false,
                null
        );
    }

    private TravelTaskState completeOn(
            TravelTaskState current,
            TravelTaskObservationType actual,
            TravelTaskObservationType expected,
            String reason
    ) {
        if (actual != expected) {
            throw illegalTransition(current, actual);
        }
        return completed(current, reason);
    }

    private TravelTaskState completed(TravelTaskState current, String reason) {
        return copy(
                current,
                current.intent(),
                TravelTaskPhase.COMPLETED,
                TravelTaskAction.RESPOND_AND_STOP,
                SessionPendingAction.NONE,
                current.origin(),
                current.destination(),
                current.quoteId(),
                current.orderId(),
                current.lastOrderStatus(),
                current.failureCount(),
                true,
                reason
        );
    }

    private TravelTaskState completedWithStatus(
            TravelTaskState current,
            RideStatus status,
            String reason
    ) {
        return copy(
                current,
                current.intent(),
                TravelTaskPhase.COMPLETED,
                TravelTaskAction.RESPOND_AND_STOP,
                SessionPendingAction.NONE,
                current.origin(),
                current.destination(),
                current.quoteId(),
                current.orderId(),
                status.name(),
                current.failureCount(),
                true,
                reason
        );
    }

    private IllegalStateException illegalTransition(
            TravelTaskState current,
            TravelTaskObservationType observation
    ) {
        return new IllegalStateException(
                "observation " + observation + " is not allowed while task phase is " + current.phase()
        );
    }

    private TravelTaskState copy(
            TravelTaskState current,
            TravelIntent intent,
            TravelTaskPhase phase,
            TravelTaskAction action,
            SessionPendingAction pendingAction,
            String origin,
            String destination,
            String quoteId,
            String orderId,
            String lastOrderStatus,
            int failureCount,
            boolean terminal,
            String terminalReason
    ) {
        return new TravelTaskState(
                current.taskId(),
                current.userId(),
                current.conversationId(),
                intent,
                phase,
                action,
                pendingAction,
                origin,
                destination,
                quoteId,
                orderId,
                lastOrderStatus,
                failureCount,
                current.version() + 1,
                terminal,
                terminalReason,
                clock.instant()
        );
    }

    private TravelTaskState read(String userId, String conversationId) {
        if (!properties.isEnabled()) {
            return null;
        }
        try {
            String json = redisTemplate.opsForValue().get(key(userId, conversationId));
            return json == null ? null : objectMapper.readValue(json, TravelTaskState.class);
        } catch (RuntimeException | JsonProcessingException exception) {
            log.warn("Redis travel task read failed for conversation {}: {}", conversationId, exception.getMessage());
            return null;
        }
    }

    private void compareAndSet(TravelTaskState expected, TravelTaskState next) {
        if (!properties.isEnabled()) {
            throw new IllegalStateException("Redis travel task state is disabled");
        }
        if (expected != null
                && (!expected.userId().equals(next.userId())
                || !expected.conversationId().equals(next.conversationId()))) {
            throw new IllegalArgumentException("travel task CAS cannot change its Redis key");
        }

        String serialized;
        try {
            serialized = objectMapper.writeValueAsString(next);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("travel task state cannot be serialized", exception);
        }

        Long result;
        try {
            result = redisTemplate.execute(
                    TASK_CAS_SCRIPT,
                    List.of(key(next.userId(), next.conversationId())),
                    expected == null ? "0" : "1",
                    expected == null ? "" : expected.taskId(),
                    expected == null ? "0" : Long.toString(expected.version()),
                    serialized,
                    Long.toString(Math.max(1L, properties.getSessionContextTtl().toMillis()))
            );
        } catch (RuntimeException exception) {
            throw new IllegalStateException("Redis travel task state is unavailable", exception);
        }

        if (Long.valueOf(1L).equals(result)) {
            return;
        }
        if (Long.valueOf(-1L).equals(result)) {
            throw new IllegalStateException("travel task does not exist or has expired");
        }
        if (Long.valueOf(-2L).equals(result)) {
            throw new IllegalStateException("travel task state is invalid");
        }
        throw new IllegalStateException("travel task CAS conflict; reload the task before retrying");
    }

    private String key(String userId, String conversationId) {
        return KEY_PREFIX + userId + ":" + conversationId;
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

    private String optional(String value, int maxLength) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim();
        if (normalized.length() > maxLength) {
            throw new IllegalArgumentException("detail must not exceed " + maxLength + " characters");
        }
        return normalized;
    }
}
