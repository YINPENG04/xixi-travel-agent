package cn.xixitravel.ride.mcp;

import cn.xixitravel.ride.context.SessionContextService;
import cn.xixitravel.ride.knowledge.KnowledgeSearchService;
import cn.xixitravel.ride.messaging.RideAsyncQueryService;
import cn.xixitravel.ride.memory.AgentMemoryService;
import cn.xixitravel.ride.service.RideService;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class McpToolConfiguration {

    @Bean
    ToolCallbackProvider xixiRideTools(
            RideService rideService,
            KnowledgeSearchService knowledgeSearchService,
            RideAsyncQueryService asyncQueryService,
            AgentMemoryService agentMemoryService,
            SessionContextService sessionContextService
    ) {
        return MethodToolCallbackProvider.builder()
                .toolObjects(new RideTools(
                        rideService,
                        knowledgeSearchService,
                        asyncQueryService,
                        agentMemoryService,
                        sessionContextService
                ))
                .build();
    }
}
