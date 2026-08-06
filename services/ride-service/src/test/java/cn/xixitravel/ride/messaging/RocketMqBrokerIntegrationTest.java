package cn.xixitravel.ride.messaging;

import cn.xixitravel.ride.api.CreateRideRequest;
import cn.xixitravel.ride.api.QuoteRequest;
import cn.xixitravel.ride.confirmation.RideConfirmationChallenge;
import cn.xixitravel.ride.domain.RideOrder;
import cn.xixitravel.ride.domain.RideQuote;
import cn.xixitravel.ride.domain.RideStatus;
import cn.xixitravel.ride.service.RideService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@SpringBootTest(properties = {
        "xixi.messaging.enabled=true",
        "xixi.messaging.topic=xixi-ride-events-it",
        "xixi.messaging.dispatch-delay-level=1",
        "xixi.messaging.outbox-poll-delay-ms=200",
        "rocketmq.name-server=127.0.0.1:9876",
        "rocketmq.producer.group=xixi-ride-it-producer"
})
@EnabledIfEnvironmentVariable(named = "XIXI_ROCKETMQ_IT", matches = "true")
@DirtiesContext
class RocketMqBrokerIntegrationTest {
    @Autowired
    private RideService rideService;

    @Autowired
    private RideAsyncQueryService asyncQueryService;

    @Test
    void publishesAndConsumesTheOrderFlowThroughARealBroker() {
        RideQuote quote = rideService.quote(
                new QuoteRequest("北京南站", "首都机场", 35.0, 50)
        ).getFirst();
        String userId = "rocketmq-user-" + UUID.randomUUID();
        String conversationId = "rocketmq-conversation-" + UUID.randomUUID();
        RideConfirmationChallenge challenge = rideService.prepareCreate(
                userId,
                conversationId,
                quote.quoteId(),
                quote.origin(),
                quote.destination()
        );
        RideOrder order = rideService.createRide(
                userId,
                "rocketmq-request-" + UUID.randomUUID(),
                new CreateRideRequest(
                        quote.quoteId(),
                        quote.origin(),
                        quote.destination(),
                        conversationId,
                        challenge.confirmationToken()
                )
        );

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(rideService.getRide(userId, order.getOrderId()).getStatus())
                        .isEqualTo(RideStatus.DRIVER_ASSIGNED)
        );
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(asyncQueryService.notifications(userId, order.getOrderId()))
                        .singleElement()
                        .satisfies(notification ->
                                assertThat(notification.type())
                                        .isEqualTo(RideEventType.DRIVER_ASSIGNED)
                        )
        );
    }
}
