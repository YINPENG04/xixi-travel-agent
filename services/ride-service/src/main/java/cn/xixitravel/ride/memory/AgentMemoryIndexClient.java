package cn.xixitravel.ride.memory;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Service
public class AgentMemoryIndexClient {
    private final RestClient restClient;
    private final boolean enabled;

    public AgentMemoryIndexClient(
            RestClient.Builder restClientBuilder,
            @Value("${xixi.memory.index-url:${xixi.knowledge-base-url:http://localhost:8090}}") String indexUrl,
            @Value("${xixi.memory.semantic-index-enabled:true}") boolean enabled
    ) {
        this.restClient = restClientBuilder.baseUrl(indexUrl).build();
        this.enabled = enabled;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void upsert(AgentMemory memory) {
        if (!enabled) {
            return;
        }
        try {
            restClient.post()
                    .uri("/api/v1/memories/upsert")
                    .body(new UpsertPayload(
                            memory.memoryId(),
                            memory.userId(),
                            memory.category().name(),
                            memory.key(),
                            memory.value(),
                            memory.version(),
                            memory.updatedAt().toEpochMilli()
                    ))
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientException exception) {
            throw new AgentMemoryIndexUnavailableException("长期记忆向量索引暂不可用", exception);
        }
    }

    public void delete(String userId, String memoryId) {
        if (!enabled) {
            return;
        }
        try {
            restClient.post()
                    .uri("/api/v1/memories/delete")
                    .body(new DeletePayload(userId, memoryId))
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientException exception) {
            throw new AgentMemoryIndexUnavailableException("长期记忆向量索引暂不可用", exception);
        }
    }

    public MemoryIndexSearchResponse search(String userId, String query, int limit) {
        if (!enabled) {
            throw new AgentMemoryIndexUnavailableException("长期记忆向量索引未启用", null);
        }
        try {
            MemoryIndexSearchResponse response = restClient.post()
                    .uri("/api/v1/memories/search")
                    .body(new SearchPayload(userId, query, limit))
                    .retrieve()
                    .body(MemoryIndexSearchResponse.class);
            if (response == null) {
                throw new AgentMemoryIndexUnavailableException("长期记忆向量索引返回了空响应", null);
            }
            return response;
        } catch (AgentMemoryIndexUnavailableException exception) {
            throw exception;
        } catch (RestClientException exception) {
            throw new AgentMemoryIndexUnavailableException("长期记忆向量索引暂不可用", exception);
        }
    }

    private record UpsertPayload(
            String memoryId,
            String userId,
            String category,
            String memoryKey,
            String memoryValue,
            long memoryVersion,
            long updatedAt
    ) {
    }

    private record DeletePayload(String userId, String memoryId) {
    }

    private record SearchPayload(String userId, String query, int limit) {
    }
}
