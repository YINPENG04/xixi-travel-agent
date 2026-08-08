package cn.xixitravel.ride.memory;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class AgentMemoryServiceIntegrationTest {
    @Autowired
    private AgentMemoryService memoryService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private AgentMemoryRepository memoryRepository;

    @Test
    void storesUpdatesAndDeletesOnlyTheCurrentUsersMemory() {
        String firstUser = "memory-user-" + UUID.randomUUID();
        String secondUser = "memory-user-" + UUID.randomUUID();

        memoryService.remember(
                firstUser,
                AgentMemoryCategory.PREFERENCE,
                "preferred_vehicle",
                "经济型",
                true
        );
        memoryService.remember(
                secondUser,
                AgentMemoryCategory.PREFERENCE,
                "preferred_vehicle",
                "舒适型",
                true
        );
        AgentMemory updated = memoryService.remember(
                firstUser,
                AgentMemoryCategory.PREFERENCE,
                "preferred_vehicle",
                "六座车型",
                true
        );

        assertThat(updated.value()).isEqualTo("六座车型");
        assertThat(memoryService.list(firstUser))
                .extracting(AgentMemory::value)
                .containsExactly("六座车型");
        assertThat(memoryService.list(secondUser))
                .extracting(AgentMemory::value)
                .containsExactly("舒适型");
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from agent_user_memories where user_id = ?",
                Integer.class,
                firstUser
        )).isEqualTo(1);

        assertThat(memoryService.forget(
                firstUser,
                AgentMemoryCategory.PREFERENCE,
                "preferred_vehicle",
                true
        )).isTrue();
        assertThat(memoryService.list(firstUser)).isEmpty();
        assertThat(memoryService.list(secondUser)).hasSize(1);
    }

    @Test
    void rejectsWritesWithoutExplicitConfirmation() {
        assertThatThrownBy(() -> memoryService.remember(
                "memory-user-" + UUID.randomUUID(),
                AgentMemoryCategory.COMMON_PLACE,
                "company",
                "中关村软件园",
                false
        ))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("confirmation");
    }

    @Test
    void rejectsSensitiveOrUnboundedFreeFormKeys() {
        assertThatThrownBy(() -> memoryService.remember(
                "memory-user-" + UUID.randomUUID(),
                AgentMemoryCategory.PREFERENCE,
                "invalid key",
                "测试",
                true
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("memory key");
    }

    @Test
    void requiresExplicitConflictResolutionAndAuditsReplacement() {
        String userId = "memory-conflict-" + UUID.randomUUID();
        memoryService.remember(
                userId,
                AgentMemoryCategory.PREFERENCE,
                "preferred_vehicle",
                "舒适型",
                true,
                0.9,
                30,
                null
        );

        assertThatThrownBy(() -> memoryService.remember(
                userId,
                AgentMemoryCategory.PREFERENCE,
                "preferred_vehicle",
                "六座车型",
                true,
                1.0,
                60,
                null
        ))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("memory conflict");

        AgentMemory replaced = memoryService.remember(
                userId,
                AgentMemoryCategory.PREFERENCE,
                "preferred_vehicle",
                "六座车型",
                true,
                1.0,
                60,
                AgentMemoryConflictResolution.REPLACE
        );

        assertThat(replaced.value()).isEqualTo("六座车型");
        assertThat(replaced.confidence()).isEqualTo(1.0);
        assertThat(replaced.expiresAt()).isAfter(Instant.now());
        assertThat(memoryService.auditTrail(userId))
                .extracting(AgentMemoryAudit::action)
                .contains(AgentMemoryAuditAction.CREATED, AgentMemoryAuditAction.REPLACED);
    }

    @Test
    void mergesOnlyAfterExplicitResolutionAndDoesNotDuplicateValues() {
        String userId = "memory-merge-" + UUID.randomUUID();
        memoryService.remember(
                userId,
                AgentMemoryCategory.PREFERENCE,
                "vehicle_features",
                "安静；新能源",
                true
        );

        AgentMemory merged = memoryService.remember(
                userId,
                AgentMemoryCategory.PREFERENCE,
                "vehicle_features",
                "新能源；空间大",
                true,
                0.8,
                365,
                AgentMemoryConflictResolution.MERGE
        );

        assertThat(merged.value()).isEqualTo("安静；新能源；空间大");
        assertThat(memoryService.auditTrail(userId))
                .extracting(AgentMemoryAudit::action)
                .contains(AgentMemoryAuditAction.MERGED);
    }

    @Test
    void rejectsSensitiveValuesBeforePersistence() {
        String userId = "memory-sensitive-" + UUID.randomUUID();

        assertThatThrownBy(() -> memoryService.remember(
                userId,
                AgentMemoryCategory.PREFERENCE,
                "contact",
                "我的手机号是13800138000",
                true
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("phone number");

        assertThat(memoryRepository.findByUserIdOrderByCategoryAscMemoryKeyAsc(userId)).isEmpty();
    }

    @Test
    void excludesExpiredMemoryAndRecordsSystemExpiry() {
        String userId = "memory-expired-" + UUID.randomUUID();
        Instant old = Instant.parse("2020-01-01T00:00:00Z");
        AgentMemoryEntity entity = memoryRepository.saveAndFlush(new AgentMemoryEntity(
                UUID.randomUUID().toString(),
                userId,
                AgentMemoryCategory.COMMON_PLACE,
                "old_company",
                "旧办公地点",
                old,
                0.9,
                old.plusSeconds(60)
        ));

        assertThat(memoryService.list(userId)).isEmpty();
        memoryService.expireDueMemories();

        assertThat(memoryRepository.findById(entity.getMemoryId()))
                .get()
                .extracting(AgentMemoryEntity::getStatus)
                .isEqualTo(AgentMemoryStatus.EXPIRED);
        assertThat(memoryService.auditTrail(userId))
                .extracting(AgentMemoryAudit::action)
                .contains(AgentMemoryAuditAction.EXPIRED);
    }
}
