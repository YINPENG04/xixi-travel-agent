package cn.xixitravel.ride.task;

import cn.xixitravel.ride.cache.RideCacheProperties;
import cn.xixitravel.ride.context.SessionPendingAction;
import cn.xixitravel.ride.intent.TravelIntentRecognizer;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TravelTaskServiceTest {
    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    private TravelTaskService service;
    private Map<String, String> values;
    private ObjectMapper objectMapper;
    private boolean failNextCas;

    @BeforeEach
    void setUp() {
        values = new HashMap<>();
        objectMapper = new ObjectMapper().findAndRegisterModules();
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(anyString())).thenAnswer(invocation -> values.get(invocation.getArgument(0)));
        doAnswer(invocation -> {
            if (failNextCas) {
                failNextCas = false;
                return 0L;
            }
            List<String> keys = invocation.getArgument(1);
            Object[] arguments = (Object[]) invocation.getRawArguments()[2];
            String stateKey = keys.getFirst();
            String current = values.get(stateKey);
            boolean expectedPresent = "1".equals(arguments[0]);

            if (!expectedPresent) {
                if (current != null) {
                    return 0L;
                }
                values.put(stateKey, (String) arguments[3]);
                return 1L;
            }
            if (current == null) {
                return -1L;
            }
            var currentState = objectMapper.readTree(current);
            if (!currentState.path("taskId").asText().equals(arguments[1])
                    || currentState.path("version").asLong() != Long.parseLong((String) arguments[2])) {
                return 0L;
            }
            values.put(stateKey, (String) arguments[3]);
            return 1L;
        }).when(redisTemplate).execute(
                any(RedisScript.class),
                any(List.class),
                any(Object[].class)
        );

        RideCacheProperties properties = new RideCacheProperties();
        properties.setEnabled(true);
        properties.setSessionContextTtl(Duration.ofMinutes(30));
        service = new TravelTaskService(
                redisTemplate,
                objectMapper,
                properties,
                new TravelIntentRecognizer(),
                Clock.fixed(Instant.parse("2026-08-08T00:00:00Z"), ZoneOffset.UTC)
        );
    }

    @Test
    void controlsBookingFromQuoteThroughConfirmationAndDispatch() {
        TravelTaskState task = service.start(
                "user-1",
                "conversation-1",
                "从虹桥机场到外滩叫车",
                null,
                null,
                null
        );
        assertThat(task.phase()).isEqualTo(TravelTaskPhase.READY_TO_QUOTE);
        assertThat(task.nextAction()).isEqualTo(TravelTaskAction.CALL_RIDE_QUOTE);

        task = service.observe(
                "user-1", "conversation-1", task.taskId(), task.version(),
                TravelTaskObservationType.QUOTE_RETURNED, null, null
        );
        assertThat(task.phase()).isEqualTo(TravelTaskPhase.WAITING_FOR_QUOTE_SELECTION);
        assertThat(task.pendingAction()).isEqualTo(SessionPendingAction.WAITING_FOR_QUOTE_CONFIRMATION);

        task = service.observe(
                "user-1", "conversation-1", task.taskId(), task.version(),
                TravelTaskObservationType.QUOTE_SELECTED, "Q-12345678", null
        );
        task = service.observe(
                "user-1", "conversation-1", task.taskId(), task.version(),
                TravelTaskObservationType.CREATE_PREPARED, null, null
        );
        assertThat(task.phase()).isEqualTo(TravelTaskPhase.WAITING_FOR_CREATE_CONFIRMATION);

        task = service.start(
                "user-1", "conversation-1", "确认下单", null, null, null
        );
        assertThat(task.phase()).isEqualTo(TravelTaskPhase.READY_TO_CREATE);

        task = service.observe(
                "user-1", "conversation-1", task.taskId(), task.version(),
                TravelTaskObservationType.ORDER_CREATED, "XIXI-ABC12345", null
        );
        assertThat(task.phase()).isEqualTo(TravelTaskPhase.WAITING_FOR_DISPATCH);

        task = service.observe(
                "user-1", "conversation-1", task.taskId(), task.version(),
                TravelTaskObservationType.ORDER_STATUS_UPDATED, null, "DRIVER_ASSIGNED"
        );
        assertThat(task.terminal()).isTrue();
        assertThat(task.phase()).isEqualTo(TravelTaskPhase.COMPLETED);
    }

    @Test
    void rejectsStaleObservationsAndStopsAfterTwoToolFailures() {
        TravelTaskState task = service.start(
                "user-2",
                "conversation-2",
                "订单状态",
                null,
                null,
                "XIXI-ABC12345"
        );

        String taskId = task.taskId();
        assertThatThrownBy(() -> service.observe(
                "user-2", "conversation-2", taskId, 99,
                TravelTaskObservationType.ORDER_STATUS_UPDATED, null, "CREATED"
        )).hasMessageContaining("identity or version conflict");

        task = service.observe(
                "user-2", "conversation-2", task.taskId(), task.version(),
                TravelTaskObservationType.TOOL_FAILED, null, "timeout"
        );
        assertThat(task.terminal()).isFalse();
        task = service.observe(
                "user-2", "conversation-2", task.taskId(), task.version(),
                TravelTaskObservationType.TOOL_FAILED, null, "timeout"
        );
        assertThat(task.terminal()).isTrue();
        assertThat(task.phase()).isEqualTo(TravelTaskPhase.FAILED);
    }

    @Test
    void rejectsObservationFromAnotherTaskEvenWhenVersionMatches() {
        TravelTaskState task = service.start(
                "user-task-id",
                "conversation-task-id",
                "查询订单状态",
                null,
                null,
                "XIXI-ABC12345"
        );

        assertThatThrownBy(() -> service.observe(
                "user-task-id",
                "conversation-task-id",
                "stale-task-id",
                task.version(),
                TravelTaskObservationType.ORDER_STATUS_UPDATED,
                null,
                "CREATED"
        )).hasMessageContaining("identity or version conflict");
    }

    @Test
    void rejectsUpdateWhenTheRedisCasLosesARace() {
        TravelTaskState task = service.start(
                "user-cas",
                "conversation-cas",
                "查询订单状态",
                null,
                null,
                "XIXI-ABC12345"
        );
        failNextCas = true;

        assertThatThrownBy(() -> service.observe(
                "user-cas",
                "conversation-cas",
                task.taskId(),
                task.version(),
                TravelTaskObservationType.ORDER_STATUS_UPDATED,
                null,
                "CREATED"
        )).hasMessageContaining("CAS conflict");
    }

    @Test
    void requiresConfirmationBeforeCancellation() {
        TravelTaskState task = service.start(
                "user-3",
                "conversation-3",
                "取消订单 XIXI-ABC12345",
                null,
                null,
                null
        );
        assertThat(task.phase()).isEqualTo(TravelTaskPhase.READY_TO_PREPARE_CANCEL);

        task = service.observe(
                "user-3", "conversation-3", task.taskId(), task.version(),
                TravelTaskObservationType.CANCEL_PREPARED, null, null
        );
        assertThat(task.phase()).isEqualTo(TravelTaskPhase.WAITING_FOR_CANCEL_CONFIRMATION);

        task = service.start(
                "user-3", "conversation-3", "确认取消", null, null, null
        );
        assertThat(task.phase()).isEqualTo(TravelTaskPhase.READY_TO_CANCEL);

        task = service.observe(
                "user-3", "conversation-3", task.taskId(), task.version(),
                TravelTaskObservationType.ORDER_CANCELLED, null, null
        );
        assertThat(task.terminal()).isTrue();
        assertThat(task.phase()).isEqualTo(TravelTaskPhase.COMPLETED);
    }
}
