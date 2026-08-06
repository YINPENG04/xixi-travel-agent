package cn.xixitravel.ride.cache;

import cn.xixitravel.ride.domain.RideQuote;
import cn.xixitravel.ride.domain.VehicleType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class RedisCacheContainerIntegrationTest {
    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>(
            DockerImageName.parse("redis:7.4-alpine")
    ).withExposedPorts(6379);

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        registry.add("xixi.cache.enabled", () -> "true");
        registry.add(
                "spring.data.redis.url",
                () -> "redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(6379)
        );
    }

    @Autowired
    private RideCacheService cacheService;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Test
    void storesQuoteWithRemainingTtlInARealRedisInstance() {
        RideQuote quote = new RideQuote(
                "Q-REDIS-IT",
                "北京南站",
                "首都机场",
                VehicleType.SIX_SEAT,
                "六座车型",
                6,
                new BigDecimal("128"),
                7,
                35.0,
                50,
                Instant.now().plusSeconds(120)
        );

        cacheService.putQuoteAfterCommit(quote);

        assertThat(cacheService.getQuote(quote.quoteId())).contains(quote);
        Long ttl = redisTemplate.getExpire("xixi:quote:" + quote.quoteId(), TimeUnit.SECONDS);
        assertThat(ttl).isNotNull().isBetween(1L, 120L);
    }
}
