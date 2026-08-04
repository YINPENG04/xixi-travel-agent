package cn.xixitravel.ride.memory;

import cn.xixitravel.ride.cache.RideCacheProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AgentMemoryCacheTest {
    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    private AgentMemoryCache cache;

    @BeforeEach
    void setUp() {
        RideCacheProperties properties = new RideCacheProperties();
        properties.setEnabled(true);
        properties.setMemoryTtl(Duration.ofMinutes(30));
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        cache = new AgentMemoryCache(
                redisTemplate,
                new ObjectMapper().findAndRegisterModules(),
                properties
        );
    }

    @Test
    void cachesAndRestoresUserMemoryWithTtl() {
        AgentMemory memory = new AgentMemory(
                "memory-1",
                "user-1",
                AgentMemoryCategory.PREFERENCE,
                "preferred_vehicle",
                "经济型",
                Instant.parse("2026-08-04T10:00:00Z"),
                1
        );

        cache.put("user-1", List.of(memory));

        ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);
        verify(valueOperations).set(
                eq("xixi:agent-memory:user-1"),
                json.capture(),
                eq(Duration.ofMinutes(30))
        );
        when(valueOperations.get("xixi:agent-memory:user-1")).thenReturn(json.getValue());

        assertThat(cache.get("user-1")).contains(List.of(memory));
    }

    @Test
    void treatsRedisFailureAsCacheMiss() {
        when(valueOperations.get("xixi:agent-memory:user-2"))
                .thenThrow(new DataAccessResourceFailureException("redis unavailable"));

        assertThat(cache.get("user-2")).isEmpty();
    }
}
