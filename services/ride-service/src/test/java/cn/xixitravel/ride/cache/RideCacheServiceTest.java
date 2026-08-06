package cn.xixitravel.ride.cache;

import cn.xixitravel.ride.domain.RideOrder;
import cn.xixitravel.ride.domain.RideQuote;
import cn.xixitravel.ride.domain.RideStatus;
import cn.xixitravel.ride.domain.VehicleType;
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

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RideCacheServiceTest {
    private static final Instant NOW = Instant.parse("2026-08-02T12:00:00Z");

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    private RideCacheService cacheService;

    @BeforeEach
    void setUp() {
        RideCacheProperties properties = new RideCacheProperties();
        properties.setEnabled(true);
        properties.setOrderTtl(Duration.ofMinutes(2));
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        cacheService = new RideCacheService(
                redisTemplate,
                new ObjectMapper().findAndRegisterModules(),
                properties,
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    @Test
    void cachesQuoteUntilItsBusinessExpiry() {
        RideQuote quote = new RideQuote(
                "Q-CACHE",
                "北京南站",
                "首都机场",
                VehicleType.ECONOMY,
                "轻享",
                4,
                new BigDecimal("42"),
                3,
                12.6,
                28,
                NOW.plus(Duration.ofMinutes(5))
        );

        cacheService.putQuoteAfterCommit(quote);

        ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);
        verify(valueOperations).set(
                eq("xixi:quote:Q-CACHE"),
                json.capture(),
                eq(Duration.ofMinutes(5))
        );
        when(valueOperations.get("xixi:quote:Q-CACHE")).thenReturn(json.getValue());

        assertThat(cacheService.getQuote("Q-CACHE")).contains(quote);
    }

    @Test
    void cachesAndRestoresOrderStatus() {
        RideOrder order = new RideOrder(
                "XIXI-CACHE",
                "user-cache",
                "Q-CACHE",
                "故宫博物院",
                "北京南站",
                VehicleType.COMFORT,
                new BigDecimal("56"),
                NOW,
                RideStatus.DRIVER_ASSIGNED
        );

        cacheService.putOrder(order);

        ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);
        verify(valueOperations).set(
                eq("xixi:order:user-cache:XIXI-CACHE"),
                json.capture(),
                eq(Duration.ofMinutes(2))
        );
        when(valueOperations.get("xixi:order:user-cache:XIXI-CACHE"))
                .thenReturn(json.getValue());

        RideOrder restored = cacheService.getOrder("user-cache", "XIXI-CACHE").orElseThrow();
        assertThat(restored.getStatus()).isEqualTo(RideStatus.DRIVER_ASSIGNED);
        assertThat(restored.getPrice()).isEqualByComparingTo("56");
    }

    @Test
    void treatsRedisFailureAsCacheMiss() {
        when(valueOperations.get("xixi:quote:Q-DOWN"))
                .thenThrow(new DataAccessResourceFailureException("redis unavailable"));

        assertThat(cacheService.getQuote("Q-DOWN")).isEmpty();
    }
}
