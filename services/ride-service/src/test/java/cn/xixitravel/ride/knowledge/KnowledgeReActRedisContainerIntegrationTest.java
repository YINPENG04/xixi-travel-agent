package cn.xixitravel.ride.knowledge;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class KnowledgeReActRedisContainerIntegrationTest {
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

    @MockBean
    private KnowledgeSearchService searchService;

    @Autowired
    private KnowledgeReActLoopService loopService;

    @Test
    void persistsTheCycleAndEnforcesTheSecondRoundInRealRedis() {
        when(searchService.search(anyString(), eq(3), eq(null)))
                .thenReturn(lowScore("南站怎么坐车"), evidenceFound("北京南站北广场在哪里上车"));

        KnowledgeReActObservation first = loopService.search(
                "redis-user",
                "redis-conversation",
                "南站怎么坐车",
                null
        );
        KnowledgeReActObservation second = loopService.search(
                "redis-user",
                "redis-conversation",
                "北京南站北广场在哪里上车",
                first.cycleId()
        );
        KnowledgeReActObservation third = loopService.search(
                "redis-user",
                "redis-conversation",
                "北京南站网约车上客区",
                first.cycleId()
        );

        assertThat(first.terminal()).isFalse();
        assertThat(second.iteration()).isEqualTo(2);
        assertThat(second.terminal()).isTrue();
        assertThat(third.stopReason()).isEqualTo("CYCLE_ALREADY_FINISHED");
    }

    private KnowledgeSearchResponse lowScore(String query) {
        return new KnowledgeSearchResponse(
                query,
                "xixi_travel_knowledge",
                "LOW_SCORE",
                "REFORMULATE_AND_RETRY_ONCE",
                0.35,
                0.10,
                "最高召回分数低于证据反馈阈值",
                List.of()
        );
    }

    private KnowledgeSearchResponse evidenceFound(String query) {
        return new KnowledgeSearchResponse(
                query,
                "xixi_travel_knowledge",
                "EVIDENCE_FOUND",
                "VERIFY_COVERAGE_THEN_ANSWER",
                0.82,
                0.12,
                "召回结果达到分数和区分度要求",
                List.of(new KnowledgeHit(
                        1,
                        "place_alias",
                        "北京南站",
                        "推荐上车点为北广场网约车上客区。",
                        0.91
                ))
        );
    }
}
