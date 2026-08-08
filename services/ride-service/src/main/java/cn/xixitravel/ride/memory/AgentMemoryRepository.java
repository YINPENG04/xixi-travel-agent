package cn.xixitravel.ride.memory;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.time.Instant;

public interface AgentMemoryRepository extends JpaRepository<AgentMemoryEntity, String> {
    List<AgentMemoryEntity> findByUserIdOrderByCategoryAscMemoryKeyAsc(String userId);

    List<AgentMemoryEntity> findTop100ByStatusAndExpiresAtLessThanEqualOrderByExpiresAtAsc(
            AgentMemoryStatus status,
            Instant expiresAt
    );

    Optional<AgentMemoryEntity> findByUserIdAndCategoryAndMemoryKey(
            String userId,
            AgentMemoryCategory category,
            String memoryKey
    );

    List<AgentMemoryEntity> findByUserIdAndMemoryIdIn(
            String userId,
            Collection<String> memoryIds
    );
}
