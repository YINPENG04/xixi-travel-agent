package cn.xixitravel.ride.knowledge;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.Set;

@Service
public class KnowledgeSearchService {
    private static final Set<String> SUPPORTED_CATEGORIES = Set.of(
            "place_alias", "vehicle", "policy", "safety", "invoice"
    );

    private final RestClient restClient;

    public KnowledgeSearchService(
            RestClient.Builder restClientBuilder,
            @Value("${xixi.knowledge-base-url:http://localhost:8090}") String knowledgeBaseUrl
    ) {
        this.restClient = restClientBuilder.baseUrl(knowledgeBaseUrl).build();
    }

    public KnowledgeSearchResponse search(String query, int limit, String category) {
        String normalizedQuery = query == null ? "" : query.trim();
        if (normalizedQuery.length() < 2 || normalizedQuery.length() > 500) {
            throw new IllegalArgumentException("知识检索问题长度必须在 2 到 500 个字符之间");
        }
        if (limit < 1 || limit > 5) {
            throw new IllegalArgumentException("知识检索结果数量必须在 1 到 5 之间");
        }
        if (category != null && !SUPPORTED_CATEGORIES.contains(category)) {
            throw new IllegalArgumentException("不支持的知识类别: " + category);
        }

        try {
            KnowledgeSearchResponse response = restClient.post()
                    .uri("/api/v1/search")
                    .body(new SearchPayload(normalizedQuery, limit, category))
                    .retrieve()
                    .body(KnowledgeSearchResponse.class);

            if (response == null) {
                throw new KnowledgeSearchUnavailableException("知识库返回了空响应", null);
            }
            return response;
        } catch (KnowledgeSearchUnavailableException exception) {
            throw exception;
        } catch (RestClientException exception) {
            throw new KnowledgeSearchUnavailableException("嘻嘻出行知识库暂不可用", exception);
        }
    }

    private record SearchPayload(String query, int limit, String category) {
    }
}
