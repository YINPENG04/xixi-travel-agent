package cn.xixitravel.ride.cache;

import cn.xixitravel.ride.domain.RideOrder;
import cn.xixitravel.ride.domain.RideQuote;
import cn.xixitravel.ride.domain.RideStatus;
import cn.xixitravel.ride.domain.VehicleType;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

@Service
public class RideCacheService {
    private static final Logger log = LoggerFactory.getLogger(RideCacheService.class);
    private static final String QUOTE_KEY_PREFIX = "xixi:quote:";
    private static final String ORDER_KEY_PREFIX = "xixi:order:";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final RideCacheProperties properties;
    private final Clock clock;

    public RideCacheService(
            StringRedisTemplate redisTemplate,
            ObjectMapper objectMapper,
            RideCacheProperties properties,
            Clock clock
    ) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.clock = clock;
    }

    public Optional<RideQuote> getQuote(String quoteId) {
        return read(quoteKey(quoteId), RideQuote.class);
    }

    public void putQuoteAfterCommit(RideQuote quote) {
        afterCommit(() -> {
            Duration ttl = Duration.between(clock.instant(), quote.expiresAt());
            if (ttl.isPositive()) {
                write(quoteKey(quote.quoteId()), quote, ttl);
            }
        });
    }

    public Optional<RideOrder> getOrder(String userId, String orderId) {
        return read(orderKey(userId, orderId), CachedRideOrder.class)
                .map(CachedRideOrder::toDomain);
    }

    public void putOrder(RideOrder order) {
        write(
                orderKey(order.getUserId(), order.getOrderId()),
                CachedRideOrder.from(order),
                properties.getOrderTtl()
        );
    }

    public void putOrderAfterCommit(RideOrder order) {
        afterCommit(() -> putOrder(order));
    }

    private <T> Optional<T> read(String key, Class<T> type) {
        if (!properties.isEnabled()) {
            return Optional.empty();
        }
        try {
            String json = redisTemplate.opsForValue().get(key);
            if (json == null) {
                return Optional.empty();
            }
            return Optional.of(objectMapper.readValue(json, type));
        } catch (RuntimeException | JsonProcessingException exception) {
            log.warn("Redis cache read failed for key {}: {}", key, exception.getMessage());
            return Optional.empty();
        }
    }

    private void write(String key, Object value, Duration ttl) {
        if (!properties.isEnabled() || ttl == null || !ttl.isPositive()) {
            return;
        }
        try {
            redisTemplate.opsForValue().set(key, objectMapper.writeValueAsString(value), ttl);
        } catch (RuntimeException | JsonProcessingException exception) {
            log.warn("Redis cache write failed for key {}: {}", key, exception.getMessage());
        }
    }

    private void afterCommit(Runnable action) {
        if (!properties.isEnabled()) {
            return;
        }
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.isSynchronizationActive()) {
            action.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                action.run();
            }
        });
    }

    private String quoteKey(String quoteId) {
        return QUOTE_KEY_PREFIX + quoteId;
    }

    private String orderKey(String userId, String orderId) {
        return ORDER_KEY_PREFIX + userId + ":" + orderId;
    }

    private record CachedRideOrder(
            String orderId,
            String userId,
            String quoteId,
            String origin,
            String destination,
            VehicleType vehicleType,
            BigDecimal price,
            Instant createdAt,
            RideStatus status
    ) {
        private static CachedRideOrder from(RideOrder order) {
            return new CachedRideOrder(
                    order.getOrderId(),
                    order.getUserId(),
                    order.getQuoteId(),
                    order.getOrigin(),
                    order.getDestination(),
                    order.getVehicleType(),
                    order.getPrice(),
                    order.getCreatedAt(),
                    order.getStatus()
            );
        }

        private RideOrder toDomain() {
            return new RideOrder(
                    orderId,
                    userId,
                    quoteId,
                    origin,
                    destination,
                    vehicleType,
                    price,
                    createdAt,
                    status
            );
        }
    }
}
