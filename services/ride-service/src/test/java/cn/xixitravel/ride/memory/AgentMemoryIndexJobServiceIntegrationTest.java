package cn.xixitravel.ride.memory;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;

@SpringBootTest
class AgentMemoryIndexJobServiceIntegrationTest {
    @Autowired
    private AgentMemoryIndexJobService jobService;

    @Autowired
    private AgentMemoryIndexJobRepository repository;

    @MockBean
    private AgentMemoryIndexClient indexClient;

    @Test
    void keepsFailedJobAndCompletesItAfterRetry() {
        AgentMemory memory = new AgentMemory(
                "memory-retry",
                "user-retry",
                AgentMemoryCategory.PREFERENCE,
                "preferred_vehicle",
                "六座车型",
                Instant.parse("2026-08-05T12:00:00Z"),
                1
        );
        String jobId = jobService.enqueueUpsert(memory);
        doThrow(new AgentMemoryIndexUnavailableException("milvus down", null))
                .when(indexClient)
                .upsert(memory);

        jobService.process(jobId);

        AgentMemoryIndexJobEntity failed = repository.findById(jobId).orElseThrow();
        assertThat(failed.getStatus()).isEqualTo(AgentMemoryIndexJobStatus.PENDING);
        assertThat(failed.getAttempts()).isEqualTo(1);

        doNothing().when(indexClient).upsert(memory);
        jobService.process(jobId);

        assertThat(repository.findById(jobId).orElseThrow().getStatus())
                .isEqualTo(AgentMemoryIndexJobStatus.COMPLETED);
    }
}
