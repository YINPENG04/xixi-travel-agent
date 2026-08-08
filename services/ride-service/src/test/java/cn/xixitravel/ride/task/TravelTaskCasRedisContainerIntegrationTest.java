package cn.xixitravel.ride.task;

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

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class TravelTaskCasRedisContainerIntegrationTest {
    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>(
            DockerImageName.parse("redis:7.4-alpine")
    ).withExposedPorts(6379);

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        registry.add("xixi.cache.enabled", () -> "true");
        registry.add("xixi.cache.session-context-ttl", () -> "5m");
        registry.add(
                "spring.data.redis.url",
                () -> "redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(6379)
        );
    }

    @Autowired
    private TravelTaskService taskService;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Test
    void commitsOnlyOneObservationForTheSameTaskVersion() throws Exception {
        String userId = "cas-user";
        String conversationId = "cas-conversation";
        String taskKey = "xixi:travel-task:" + userId + ":" + conversationId;
        TravelTaskState initial = taskService.start(
                userId,
                conversationId,
                "从虹桥机场到外滩叫车",
                null,
                null,
                null
        );
        assertThat(initial.phase()).isEqualTo(TravelTaskPhase.READY_TO_QUOTE);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<Boolean> first = executor.submit(() -> observeQuote(initial, ready, start));
            Future<Boolean> second = executor.submit(() -> observeQuote(initial, ready, start));

            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            assertThat(List.of(first.get(), second.get()))
                    .containsExactlyInAnyOrder(true, false);

            TravelTaskState stored = taskService.get(userId, conversationId).task();
            assertThat(stored.version()).isEqualTo(initial.version() + 1);
            assertThat(stored.phase()).isEqualTo(TravelTaskPhase.WAITING_FOR_QUOTE_SELECTION);
            assertThat(redisTemplate.getExpire(taskKey, TimeUnit.SECONDS))
                    .isNotNull()
                    .isBetween(1L, 300L);
        } finally {
            executor.shutdownNow();
            redisTemplate.delete(taskKey);
        }
    }

    private boolean observeQuote(
            TravelTaskState initial,
            CountDownLatch ready,
            CountDownLatch start
    ) throws InterruptedException {
        ready.countDown();
        start.await();
        try {
            taskService.observe(
                    initial.userId(),
                    initial.conversationId(),
                    initial.taskId(),
                    initial.version(),
                    TravelTaskObservationType.QUOTE_RETURNED,
                    null,
                    null
            );
            return true;
        } catch (IllegalStateException exception) {
            return false;
        }
    }
}
