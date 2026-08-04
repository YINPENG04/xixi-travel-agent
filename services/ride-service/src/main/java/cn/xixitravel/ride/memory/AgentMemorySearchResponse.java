package cn.xixitravel.ride.memory;

import java.util.List;

public record AgentMemorySearchResponse(
        String query,
        boolean semanticIndexAvailable,
        boolean cached,
        List<RelevantAgentMemory> hits
) {
}
