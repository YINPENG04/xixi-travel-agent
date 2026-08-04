package cn.xixitravel.ride.context;

import cn.xixitravel.ride.cache.RideCacheProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SessionContextServiceTest {
    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    private SessionContextService service;

    @BeforeEach
    void setUp() {
        RideCacheProperties properties = new RideCacheProperties();
        properties.setEnabled(true);
        properties.setSessionContextTtl(Duration.ofMinutes(30));
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        service = new SessionContextService(
                redisTemplate,
                new ObjectMapper().findAndRegisterModules(),
                properties,
                Clock.fixed(Instant.parse("2026-08-04T12:00:00Z"), ZoneOffset.UTC)
        );
    }

    @Test
    void storesAndRestoresSessionScopedWorkingState() {
        SessionContextState saved = service.save(
                "user-1",
                "conversation-1",
                "quote-1",
                "",
                SessionPendingAction.WAITING_FOR_ORDER_CONFIRMATION,
                "用户正在确认六座车型报价",
                12
        );

        ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);
        verify(valueOperations).set(
                eq("xixi:session-context:user-1:conversation-1"),
                json.capture(),
                eq(Duration.ofMinutes(30))
        );
        when(valueOperations.get("xixi:session-context:user-1:conversation-1"))
                .thenReturn(json.getValue());

        SessionContextLookup lookup = service.get("user-1", "conversation-1");
        assertThat(lookup.found()).isTrue();
        assertThat(lookup.context()).isEqualTo(saved);
        assertThat(lookup.context().activeOrderId()).isNull();
    }
}
