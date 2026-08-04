package cn.xixitravel.ride.memory;

import java.util.List;

public record MemoryIndexSearchResponse(
        String query,
        String collection,
        List<IndexedMemoryHit> hits
) {
}
