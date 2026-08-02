package cn.xixitravel.ride.messaging;

import cn.xixitravel.ride.api.CreateRideRequest;
import cn.xixitravel.ride.api.QuoteRequest;
import cn.xixitravel.ride.domain.RideOrder;
import cn.xixitravel.ride.domain.RideQuote;
import cn.xixitravel.ride.domain.RideStatus;
import cn.xixitravel.ride.service.RideService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class RideEventProcessorIntegrationTest {
    @Autowired
    private RideService rideService;

    @Autowired
    private RideOutboxRepository outboxRepository;

    @Autowired
    private RideEventCodec eventCodec;

    @Autowired
    private RideEventProcessor eventProcessor;

    @Autowired
    private RideAsyncQueryService asyncQueryService;

    @Test
    void dispatchesAndNotifiesOnlyOnceForDuplicateMessages() {
        CreatedRide created = createRide();
        RideEventMessage orderCreated = event(created.order().getOrderId(), RideEventType.ORDER_CREATED);

        eventProcessor.processDispatchEvent(orderCreated);
        eventProcessor.processDispatchEvent(orderCreated);

        assertThat(rideService.getRide(created.userId(), created.order().getOrderId()).getStatus())
                .isEqualTo(RideStatus.DRIVER_ASSIGNED);
        assertThat(events(created.order().getOrderId(), RideEventType.DRIVER_ASSIGNED)).hasSize(1);

        RideEventMessage driverAssigned = event(
                created.order().getOrderId(),
                RideEventType.DRIVER_ASSIGNED
        );
        eventProcessor.processNotificationEvent(driverAssigned);
        eventProcessor.processNotificationEvent(driverAssigned);

        assertThat(asyncQueryService.notifications(created.userId(), created.order().getOrderId()))
                .singleElement()
                .satisfies(notification -> {
                    assertThat(notification.type()).isEqualTo(RideEventType.DRIVER_ASSIGNED);
                    assertThat(notification.message()).contains("司机已接单");
                });
    }

    @Test
    void timeoutCancelsOnlyOrdersThatAreStillCreated() {
        CreatedRide created = createRide();
        RideEventMessage timeout = event(
                created.order().getOrderId(),
                RideEventType.ORDER_TIMEOUT_CHECK
        );

        eventProcessor.processDispatchEvent(timeout);
        eventProcessor.processDispatchEvent(event(
                created.order().getOrderId(),
                RideEventType.ORDER_CREATED
        ));

        assertThat(rideService.getRide(created.userId(), created.order().getOrderId()).getStatus())
                .isEqualTo(RideStatus.CANCELLED);
        assertThat(events(created.order().getOrderId(), RideEventType.ORDER_CANCELLED)).hasSize(1);
    }

    @Test
    void completedRideCreatesInvoiceEligibilityOnlyOnce() {
        CreatedRide created = createRide();
        String orderId = created.order().getOrderId();
        rideService.transition(orderId, RideStatus.DRIVER_ASSIGNED);
        rideService.transition(orderId, RideStatus.DRIVER_ARRIVING);
        rideService.transition(orderId, RideStatus.DRIVER_ARRIVED);
        rideService.transition(orderId, RideStatus.IN_PROGRESS);
        rideService.transition(orderId, RideStatus.COMPLETED);
        RideEventMessage completed = event(orderId, RideEventType.RIDE_COMPLETED);

        eventProcessor.processInvoiceEvent(completed);
        eventProcessor.processInvoiceEvent(completed);

        RideInvoiceEligibility eligibility = asyncQueryService.invoiceEligibility(
                created.userId(),
                orderId
        );
        assertThat(eligibility.eligible()).isTrue();
        assertThat(eligibility.amount()).isEqualByComparingTo(created.order().getPrice());
        assertThat(eligibility.status()).isEqualTo("ELIGIBLE");
    }

    private CreatedRide createRide() {
        RideQuote quote = rideService.quote(
                new QuoteRequest("故宫博物院", "北京南站", 12.6, 28)
        ).getFirst();
        String userId = "event-user-" + UUID.randomUUID();
        RideOrder order = rideService.createRide(
                userId,
                "event-request-" + UUID.randomUUID(),
                new CreateRideRequest(quote.quoteId(), "故宫博物院", "北京南站")
        );
        return new CreatedRide(userId, order);
    }

    private RideEventMessage event(String orderId, RideEventType type) {
        return events(orderId, type).stream()
                .findFirst()
                .map(RideOutboxEntity::getPayload)
                .map(eventCodec::decode)
                .orElseThrow();
    }

    private java.util.List<RideOutboxEntity> events(String orderId, RideEventType type) {
        return outboxRepository.findByAggregateIdOrderByCreatedAt(orderId).stream()
                .filter(event -> event.getEventType() == type)
                .toList();
    }

    private record CreatedRide(String userId, RideOrder order) {
    }
}
