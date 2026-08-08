package cn.xixitravel.ride.intent;

import java.util.List;
import java.util.Map;

public record IntentRecognitionResult(
        TravelIntent intent,
        double confidence,
        boolean ambiguous,
        List<TravelIntent> alternatives,
        Map<String, String> extractedSlots,
        List<String> missingSlots,
        String recommendedTool,
        String reason
) {
}
