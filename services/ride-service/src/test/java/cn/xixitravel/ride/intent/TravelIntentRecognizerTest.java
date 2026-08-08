package cn.xixitravel.ride.intent;

import cn.xixitravel.ride.context.SessionPendingAction;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TravelIntentRecognizerTest {
    private final TravelIntentRecognizer recognizer = new TravelIntentRecognizer();

    @Test
    void recognizesBookingAndExtractsRouteSlots() {
        IntentRecognitionResult result = recognizer.recognize(
                "请帮我从虹桥机场到外滩叫车",
                SessionPendingAction.NONE,
                null,
                null,
                null
        );

        assertThat(result.intent()).isEqualTo(TravelIntent.RIDE_CREATE);
        assertThat(result.ambiguous()).isFalse();
        assertThat(result.extractedSlots())
                .containsEntry("origin", "虹桥机场")
                .containsEntry("destination", "外滩");
        assertThat(result.missingSlots()).isEmpty();
        assertThat(result.recommendedTool()).isEqualTo("rideQuote");
    }

    @Test
    void recognizesConfirmationOnlyWhenAnActionIsPending() {
        assertThat(recognizer.recognize(
                "确认，继续",
                SessionPendingAction.WAITING_FOR_ORDER_CONFIRMATION,
                null,
                null,
                null
        ).intent()).isEqualTo(TravelIntent.CONFIRM_PENDING_ACTION);

        assertThat(recognizer.recognize(
                "确认，继续",
                SessionPendingAction.NONE,
                null,
                null,
                null
        ).intent()).isEqualTo(TravelIntent.UNKNOWN);
    }

    @Test
    void marksCompetingSideEffectIntentsAsAmbiguous() {
        IntentRecognitionResult result = recognizer.recognize(
                "取消订单并开发票",
                SessionPendingAction.NONE,
                null,
                null,
                "XIXI-ABC12345"
        );

        assertThat(result.ambiguous()).isTrue();
        assertThat(result.alternatives())
                .contains(TravelIntent.RIDE_CANCEL, TravelIntent.QUERY_INVOICE);
    }
}
