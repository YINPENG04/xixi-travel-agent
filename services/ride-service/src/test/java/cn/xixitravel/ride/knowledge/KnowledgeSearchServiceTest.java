package cn.xixitravel.ride.knowledge;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class KnowledgeSearchServiceTest {

    @Test
    void returnsStructuredKnowledgeHits() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        KnowledgeSearchService service = new KnowledgeSearchService(
                builder,
                "http://knowledge-service:8090"
        );

        server.expect(requestTo("http://knowledge-service:8090/api/v1/search"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().json("""
                        {"query":"发票怎么开","limit":3,"category":null}
                        """))
                .andRespond(withSuccess("""
                        {
                          "query": "发票怎么开",
                          "collection": "xixi_travel_knowledge",
                          "retrievalStatus": "EVIDENCE_FOUND",
                          "recommendedNextAction": "VERIFY_COVERAGE_THEN_ANSWER",
                          "topScore": 0.91,
                          "scoreGap": 0.22,
                          "observationReason": "召回结果达到分数和区分度要求",
                          "hits": [
                            {
                              "id": 7,
                              "category": "invoice",
                              "title": "电子发票",
                              "content": "行程完成后可申请电子发票。",
                              "score": 0.91
                            }
                          ]
                        }
                        """, MediaType.APPLICATION_JSON));

        KnowledgeSearchResponse response = service.search("发票怎么开", 3, null);

        assertThat(response.collection()).isEqualTo("xixi_travel_knowledge");
        assertThat(response.retrievalStatus()).isEqualTo("EVIDENCE_FOUND");
        assertThat(response.recommendedNextAction())
                .isEqualTo("VERIFY_COVERAGE_THEN_ANSWER");
        assertThat(response.topScore()).isEqualTo(0.91);
        assertThat(response.scoreGap()).isEqualTo(0.22);
        assertThat(response.hits()).singleElement().satisfies(hit -> {
            assertThat(hit.category()).isEqualTo("invoice");
            assertThat(hit.title()).isEqualTo("电子发票");
            assertThat(hit.score()).isEqualTo(0.91);
        });
        server.verify();
    }
}
