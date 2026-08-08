package cn.xixitravel.ride.memory;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AgentMemoryAuditRepository extends JpaRepository<AgentMemoryAuditEntity, String> {
    List<AgentMemoryAuditEntity> findTop100ByUserIdOrderByOccurredAtDesc(String userId);
}
