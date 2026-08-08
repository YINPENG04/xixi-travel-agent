package cn.xixitravel.ride.mcp;

import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class McpToolConfigurationTest {
    @Autowired
    private ToolCallbackProvider toolCallbackProvider;

    @Test
    void registersTravelMemoryToolsWithTheRideTools() {
        assertThat(Arrays.stream(toolCallbackProvider.getToolCallbacks())
                .map(callback -> callback.getToolDefinition().name()))
                .contains(
                        "ridePrepareCreate",
                        "ridePrepareCancel",
                        "travelMemoryList",
                        "travelMemorySearch",
                        "travelMemoryRemember",
                        "travelMemoryForget",
                        "travelMemoryAudit",
                        "travelIntentRecognize",
                        "travelTaskStart",
                        "travelTaskGet",
                        "travelTaskObserve",
                        "travelSessionContextGet",
                        "travelSessionContextSave"
                );
    }
}
