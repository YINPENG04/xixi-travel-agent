package cn.xixitravel.ride.mcp;

import cn.xixitravel.ride.context.SessionContextService;
import cn.xixitravel.ride.knowledge.KnowledgeReActLoopService;
import cn.xixitravel.ride.messaging.RideAsyncQueryService;
import cn.xixitravel.ride.memory.AgentMemoryService;
import cn.xixitravel.ride.service.RideService;
import cn.xixitravel.ride.task.TravelTaskService;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class McpToolConfiguration {

    @Bean
    ToolCallbackProvider xixiRideTools(
            RideService rideService,
            KnowledgeReActLoopService knowledgeReActLoopService,
            RideAsyncQueryService asyncQueryService,
            AgentMemoryService agentMemoryService,
            SessionContextService sessionContextService,
            TravelTaskService travelTaskService
    ) {
        return MethodToolCallbackProvider.builder()
                .toolObjects(new RideTools(
                        rideService,
                        knowledgeReActLoopService,
                        asyncQueryService,
                        agentMemoryService,
                        sessionContextService,
                        travelTaskService
                ))
                .build();
    }
}
