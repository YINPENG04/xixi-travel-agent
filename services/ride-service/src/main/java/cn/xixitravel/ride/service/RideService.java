package cn.xixitravel.ride.service;

import cn.xixitravel.ride.api.CreateRideRequest;
import cn.xixitravel.ride.api.QuoteRequest;
import cn.xixitravel.ride.domain.RideOrder;
import cn.xixitravel.ride.domain.RideQuote;
import cn.xixitravel.ride.domain.RideStatus;
import cn.xixitravel.ride.domain.VehicleType;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class RideService {
    private static final BigDecimal BASE_FARE = new BigDecimal("12.00");
    private static final BigDecimal PER_KILOMETER = new BigDecimal("2.38");
    private static final Duration QUOTE_TTL = Duration.ofMinutes(5);

    private final Clock clock;
    private final Map<String, RideQuote> quotes = new ConcurrentHashMap<>();
    private final Map<String, RideOrder> orders = new ConcurrentHashMap<>();
    private final Map<String, String> idempotencyKeys = new ConcurrentHashMap<>();

    public RideService() {
        this(Clock.systemUTC());
    }

    RideService(Clock clock) {
        this.clock = clock;
    }

    public List<RideQuote> quote(QuoteRequest request) {
        Instant expiresAt = clock.instant().plus(QUOTE_TTL);

        return Arrays.stream(VehicleType.values())
                .map(type -> {
                    BigDecimal base = BASE_FARE.add(
                            PER_KILOMETER.multiply(BigDecimal.valueOf(request.distanceKilometers()))
                    );
                    BigDecimal price = base.multiply(type.priceMultiplier())
                            .setScale(0, RoundingMode.HALF_UP);
                    RideQuote quote = new RideQuote(
                            "Q-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(),
                            type,
                            type.displayName(),
                            type.seats(),
                            price,
                            switch (type) {
                                case ECONOMY -> 3;
                                case COMFORT -> 5;
                                case SIX_SEAT -> 7;
                            },
                            request.distanceKilometers(),
                            request.durationMinutes(),
                            expiresAt
                    );
                    quotes.put(quote.quoteId(), quote);
                    return quote;
                })
                .toList();
    }

    public RideOrder createRide(
            String userId,
            String idempotencyKey,
            CreateRideRequest request
    ) {
        String existingOrderId = idempotencyKeys.get(scopedKey(userId, idempotencyKey));
        if (existingOrderId != null) {
            return getRide(userId, existingOrderId);
        }

        RideQuote quote = requireValidQuote(request.quoteId());
        RideOrder order = new RideOrder(
                "XIXI-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(),
                userId,
                quote.quoteId(),
                request.origin(),
                request.destination(),
                quote.vehicleType(),
                quote.price(),
                clock.instant()
        );
        orders.put(order.getOrderId(), order);
        idempotencyKeys.putIfAbsent(scopedKey(userId, idempotencyKey), order.getOrderId());
        return orders.get(idempotencyKeys.get(scopedKey(userId, idempotencyKey)));
    }

    public RideOrder getRide(String userId, String orderId) {
        RideOrder order = orders.get(orderId);
        if (order == null || !order.getUserId().equals(userId)) {
            throw new RideNotFoundException(orderId);
        }
        return order;
    }

    public RideOrder cancel(String userId, String orderId) {
        return getRide(userId, orderId).transitionTo(RideStatus.CANCELLED);
    }

    public RideOrder transition(String orderId, RideStatus status) {
        RideOrder order = orders.get(orderId);
        if (order == null) {
            throw new RideNotFoundException(orderId);
        }
        return order.transitionTo(status);
    }

    private RideQuote requireValidQuote(String quoteId) {
        RideQuote quote = quotes.get(quoteId);
        if (quote == null) {
            throw new IllegalArgumentException("报价不存在：" + quoteId);
        }
        if (quote.isExpired(clock.instant())) {
            throw new IllegalArgumentException("报价已过期，请重新询价");
        }
        return quote;
    }

    private String scopedKey(String userId, String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("缺少 Idempotency-Key");
        }
        return userId + ":" + idempotencyKey;
    }
}
