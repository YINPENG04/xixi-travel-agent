package cn.xixitravel.ride.api;

import cn.xixitravel.ride.knowledge.KnowledgeSearchResponse;
import cn.xixitravel.ride.knowledge.KnowledgeSearchService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/knowledge")
public class KnowledgeController {
    private final KnowledgeSearchService knowledgeSearchService;

    public KnowledgeController(KnowledgeSearchService knowledgeSearchService) {
        this.knowledgeSearchService = knowledgeSearchService;
    }

    @PostMapping("/search")
    public KnowledgeSearchResponse search(@Valid @RequestBody KnowledgeQueryRequest request) {
        return knowledgeSearchService.search(
                request.query(),
                request.resolvedLimit(),
                request.resolvedCategory()
        );
    }
}
