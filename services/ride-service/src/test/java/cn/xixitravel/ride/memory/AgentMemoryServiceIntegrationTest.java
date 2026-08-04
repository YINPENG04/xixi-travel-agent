package cn.xixitravel.ride.memory;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class AgentMemoryServiceIntegrationTest {
    @Autowired
    private AgentMemoryService memoryService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

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
}
