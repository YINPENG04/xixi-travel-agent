package cn.xixitravel.ride.service;

import cn.xixitravel.ride.api.CreateRideRequest;
import cn.xixitravel.ride.api.QuoteRequest;
import cn.xixitravel.ride.domain.RideOrder;
import cn.xixitravel.ride.domain.RideQuote;
import cn.xixitravel.ride.domain.RideStatus;
import cn.xixitravel.ride.confirmation.RideConfirmationChallenge;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class RidePersistenceIntegrationTest {
    @Autowired
    private RideService rideService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void persistsQuotesAndKeepsRideCreationIdempotent() {
        List<RideQuote> quotes = rideService.quote(
                new QuoteRequest("故宫博物院", "北京南站", 12.6, 28)
        );
        RideQuote quote = quotes.getFirst();
        String userId = "user-" + UUID.randomUUID();
        String idempotencyKey = "request-" + UUID.randomUUID();
        CreateRideRequest request = confirmedRequest(
                userId,
                "conversation-" + UUID.randomUUID(),
                quote,
                "故宫博物院",
                "北京南站"
        );

        RideOrder first = rideService.createRide(userId, idempotencyKey, request);
        RideOrder repeated = rideService.createRide(userId, idempotencyKey, request);

        assertThat(repeated.getOrderId()).isEqualTo(first.getOrderId());
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from ride_quotes where quote_id = ?",
                Integer.class,
                quote.quoteId()
        )).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from ride_orders where user_id = ? and idempotency_key = ?",
                Integer.class,
                userId,
                idempotencyKey
        )).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from ride_outbox_events where aggregate_id = ?",
                Integer.class,
                first.getOrderId()
        )).isEqualTo(2);
    }

    @Test
    void persistsOrderStatusTransitions() {
        RideQuote quote = rideService.quote(
                new QuoteRequest("首都机场", "国贸", 26.0, 45)
        ).getFirst();
        String userId = "user-" + UUID.randomUUID();
        RideOrder order = rideService.createRide(
                userId,
                "request-" + UUID.randomUUID(),
                confirmedRequest(
                        userId,
                        "conversation-" + UUID.randomUUID(),
                        quote,
                        "首都机场",
                        "国贸"
                )
        );

        rideService.transition(order.getOrderId(), RideStatus.DRIVER_ASSIGNED);
        RideOrder reloaded = rideService.getRide(userId, order.getOrderId());

        assertThat(reloaded.getStatus()).isEqualTo(RideStatus.DRIVER_ASSIGNED);
        assertThat(jdbcTemplate.queryForObject(
                "select status from ride_orders where order_id = ?",
                String.class,
                order.getOrderId()
        )).isEqualTo("DRIVER_ASSIGNED");
        assertThat(jdbcTemplate.queryForObject(
                "select lock_version from ride_orders where order_id = ?",
                Long.class,
                order.getOrderId()
        )).isEqualTo(1L);
    }

    @Test
    void rejectsRouteChangesAndIdempotencyKeyReuseWithDifferentPayload() {
        RideQuote firstQuote = rideService.quote(
                new QuoteRequest("北京南站", "首都机场", 35.0, 50)
        ).getFirst();
        String userId = "user-" + UUID.randomUUID();
        String idempotencyKey = "request-" + UUID.randomUUID();
        String conversationId = "conversation-" + UUID.randomUUID();

        assertThatThrownBy(() -> rideService.prepareCreate(
                userId,
                conversationId,
                firstQuote.quoteId(),
                "北京南站",
                "国贸"
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("报价快照");

        CreateRideRequest firstRequest = confirmedRequest(
                userId,
                conversationId,
                firstQuote,
                "北京南站",
                "首都机场"
        );
        rideService.createRide(userId, idempotencyKey, firstRequest);

        RideQuote secondQuote = rideService.quote(
                new QuoteRequest("北京南站", "国贸", 15.0, 30)
        ).getFirst();
        assertThatThrownBy(() -> rideService.createRide(
                userId,
                idempotencyKey,
                new CreateRideRequest(
                        secondQuote.quoteId(),
                        secondQuote.origin(),
                        secondQuote.destination(),
                        conversationId,
                        "unused-for-conflict"
                )
        )).isInstanceOf(IdempotencyConflictException.class);
    }

    @Test
    void confirmationTokenIsBoundToThePreparedRequestAndConsumedOnce() {
        RideQuote quote = rideService.quote(
                new QuoteRequest("西直门", "北京南站", 12.0, 25)
        ).getFirst();
        String userId = "user-" + UUID.randomUUID();
        String conversationId = "conversation-" + UUID.randomUUID();
        RideConfirmationChallenge challenge = rideService.prepareCreate(
                userId,
                conversationId,
                quote.quoteId(),
                quote.origin(),
                quote.destination()
        );

        assertThatThrownBy(() -> rideService.createRide(
                userId,
                "request-" + UUID.randomUUID(),
                new CreateRideRequest(
                        quote.quoteId(),
                        quote.origin(),
                        quote.destination(),
                        "different-conversation",
                        challenge.confirmationToken()
                )
        )).hasMessageContaining("不匹配");

        RideOrder created = rideService.createRide(
                userId,
                "request-" + UUID.randomUUID(),
                new CreateRideRequest(
                        quote.quoteId(),
                        quote.origin(),
                        quote.destination(),
                        conversationId,
                        challenge.confirmationToken()
                )
        );
        assertThat(created.getOrigin()).isEqualTo(quote.origin());

        assertThatThrownBy(() -> rideService.createRide(
                userId,
                "another-request-" + UUID.randomUUID(),
                new CreateRideRequest(
                        quote.quoteId(),
                        quote.origin(),
                        quote.destination(),
                        conversationId,
                        challenge.confirmationToken()
                )
        )).hasMessageContaining("已经使用");
    }

    private CreateRideRequest confirmedRequest(
            String userId,
            String conversationId,
            RideQuote quote,
            String origin,
            String destination
    ) {
        RideConfirmationChallenge challenge = rideService.prepareCreate(
                userId,
                conversationId,
                quote.quoteId(),
                origin,
                destination
        );
        return new CreateRideRequest(
                quote.quoteId(),
                origin,
                destination,
                conversationId,
                challenge.confirmationToken()
        );
    }
}
