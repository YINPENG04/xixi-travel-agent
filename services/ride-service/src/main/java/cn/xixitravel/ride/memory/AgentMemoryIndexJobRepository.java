package cn.xixitravel.ride.memory;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;

public interface AgentMemoryIndexJobRepository
        extends JpaRepository<AgentMemoryIndexJobEntity, String> {
    List<AgentMemoryIndexJobEntity> findTop20ByStatusAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(
            AgentMemoryIndexJobStatus status,
            Instant now
    );
}
