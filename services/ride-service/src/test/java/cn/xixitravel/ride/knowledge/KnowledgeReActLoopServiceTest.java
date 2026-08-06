package cn.xixitravel.ride.knowledge;

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
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class KnowledgeReActLoopServiceTest {
    @Mock
    private KnowledgeSearchService searchService;

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    private KnowledgeReActLoopService loopService;

    @BeforeEach
    void setUp() {
        RideCacheProperties properties = new RideCacheProperties();
        properties.setEnabled(true);
        properties.setReactRagTtl(Duration.ofMinutes(10));
        properties.setReactRagLockTtl(Duration.ofSeconds(30));
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        loopService = new KnowledgeReActLoopService(
                searchService,
                redisTemplate,
                new ObjectMapper().findAndRegisterModules(),
                properties,
                Clock.fixed(Instant.parse("2026-08-06T00:00:00Z"), ZoneOffset.UTC)
        );
    }

    @Test
    void allowsOneRewrittenRetryAndThenTerminates() {
        when(searchService.search(anyString(), eq(3), eq(null)))
                .thenReturn(lowScore("南站怎么坐车"), evidenceFound("北京南站北广场在哪里上车"));

        KnowledgeReActObservation first = loopService.search(
                "user-1",
                "conversation-1",
                "南站怎么坐车",
                null
        );

        assertThat(first.iteration()).isEqualTo(1);
        assertThat(first.terminal()).isFalse();
        assertThat(first.recommendedNextAction())
                .isEqualTo("REFORMULATE_QUERY_AND_RETRY_WITH_CYCLE_ID");

        String stateKey = "xixi:react-rag:user-1:conversation-1:" + first.cycleId();
        ArgumentCaptor<String> stateJson = ArgumentCaptor.forClass(String.class);
        verify(valueOperations).set(
                eq(stateKey),
                stateJson.capture(),
                eq(Duration.ofMinutes(10))
        );
        when(valueOperations.get(stateKey)).thenReturn(stateJson.getValue());
        when(valueOperations.setIfAbsent(
                stateKey + ":lock",
                "1",
                Duration.ofSeconds(30)
        )).thenReturn(true);

        KnowledgeReActObservation second = loopService.search(
                "user-1",
                "conversation-1",
                "北京南站北广场在哪里上车",
                first.cycleId()
        );

        assertThat(second.iteration()).isEqualTo(2);
        assertThat(second.terminal()).isTrue();
        assertThat(second.stopReason()).isEqualTo("EVIDENCE_READY");
        assertThat(second.retrievalStatus()).isEqualTo("EVIDENCE_FOUND");
        verify(searchService, times(2)).search(anyString(), eq(3), eq(null));
        verify(redisTemplate).delete(stateKey + ":lock");
    }

    @Test
    void rejectsAnUnchangedRetryWithoutCallingRagAgain() {
        when(searchService.search(anyString(), eq(3), eq(null)))
                .thenReturn(lowScore("发票"));

        KnowledgeReActObservation first = loopService.search(
                "user-2",
                "conversation-2",
                "发票",
                null
        );
        String stateKey = "xixi:react-rag:user-2:conversation-2:" + first.cycleId();
        ArgumentCaptor<String> stateJson = ArgumentCaptor.forClass(String.class);
        verify(valueOperations).set(
                eq(stateKey),
                stateJson.capture(),
                eq(Duration.ofMinutes(10))
        );
        when(valueOperations.get(stateKey)).thenReturn(stateJson.getValue());

        KnowledgeReActObservation rejected = loopService.search(
                "user-2",
                "conversation-2",
                "发票",
                first.cycleId()
        );

        assertThat(rejected.terminal()).isTrue();
        assertThat(rejected.stopReason()).isEqualTo("QUERY_NOT_REFORMULATED");
        assertThat(rejected.retrievalStatus()).isEqualTo("RETRY_REJECTED");
        verify(searchService).search(anyString(), eq(3), eq(null));
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
