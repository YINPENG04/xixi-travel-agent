package cn.xixitravel.ride.memory;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AgentMemorySearchTest {
    @Mock
    private AgentMemoryRepository repository;

    @Mock
    private AgentMemoryCache cache;

    @Mock
    private AgentMemoryIndexJobService indexJobService;

    private AgentMemoryService service;

    @BeforeEach
    void setUp() {
        service = new AgentMemoryService(
                repository,
                cache,
                indexJobService,
                Clock.fixed(Instant.parse("2026-08-04T12:00:00Z"), ZoneOffset.UTC)
        );
    }

    @Test
    void verifiesMilvusCandidatesAgainstMySqlVersionAndReturnsTopThree() {
        AgentMemoryEntity current = new AgentMemoryEntity(
                "memory-current",
                "user-1",
                AgentMemoryCategory.PREFERENCE,
                "luggage_vehicle",
                "携带大件行李时优先六座车型",
                Instant.parse("2026-08-04T10:00:00Z")
        );
        current.updateValue(
                "携带大件行李时优先六座车型",
                Instant.parse("2026-08-04T11:00:00Z")
        );
        AgentMemoryEntity stale = new AgentMemoryEntity(
                "memory-stale",
                "user-1",
                AgentMemoryCategory.PREFERENCE,
                "old_preference",
                "旧偏好",
                Instant.parse("2026-08-03T10:00:00Z")
        );

        when(cache.currentVersion("user-1")).thenReturn(7L);
        when(cache.getSearch(eq("user-1"), eq(7L), org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(Optional.empty());
        when(indexJobService.search("user-1", "今天有两个大箱子", 5))
                .thenReturn(new MemoryIndexSearchResponse(
                        "今天有两个大箱子",
                        "xixi_user_memories",
                        List.of(
                                new IndexedMemoryHit("memory-current", 2, 0.91),
                                new IndexedMemoryHit("memory-stale", 99, 0.99)
                        )
                ));
        when(repository.findByUserIdAndMemoryIdIn(eq("user-1"), anyCollection()))
                .thenReturn(List.of(current, stale));

        AgentMemorySearchResponse response = service.search("user-1", "今天有两个大箱子");

        assertThat(response.semanticIndexAvailable()).isTrue();
        assertThat(response.cached()).isFalse();
        assertThat(response.hits())
                .extracting(result -> result.memory().memoryId())
                .containsExactly("memory-current");
        verify(cache).putSearch(
                eq("user-1"),
                eq(7L),
                org.mockito.ArgumentMatchers.anyString(),
                eq(response.hits())
        );
    }

    @Test
    void reportsUnavailableIndexWithoutReturningUnverifiedMemory() {
        when(cache.currentVersion("user-1")).thenReturn(0L);
        when(cache.getSearch(eq("user-1"), eq(0L), org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(Optional.empty());
        when(indexJobService.search("user-1", "推荐车型", 5))
                .thenThrow(new AgentMemoryIndexUnavailableException("down", null));

        AgentMemorySearchResponse response = service.search("user-1", "推荐车型");

        assertThat(response.semanticIndexAvailable()).isFalse();
        assertThat(response.hits()).isEmpty();
    }
}
