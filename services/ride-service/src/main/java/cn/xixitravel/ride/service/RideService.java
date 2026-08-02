package cn.xixitravel.ride.service;

import cn.xixitravel.ride.api.CreateRideRequest;
import cn.xixitravel.ride.api.QuoteRequest;
import cn.xixitravel.ride.domain.RideOrder;
import cn.xixitravel.ride.domain.RideQuote;
import cn.xixitravel.ride.domain.RideStatus;
import cn.xixitravel.ride.domain.VehicleType;
import cn.xixitravel.ride.messaging.RideEventType;
import cn.xixitravel.ride.messaging.RideMessagingProperties;
import cn.xixitravel.ride.messaging.RideOutboxService;
import cn.xixitravel.ride.persistence.RideOrderEntity;
import cn.xixitravel.ride.persistence.RideOrderRepository;
import cn.xixitravel.ride.persistence.RideQuoteEntity;
import cn.xixitravel.ride.persistence.RideQuoteRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Service
public class RideService {
    private static final BigDecimal BASE_FARE = new BigDecimal("12.00");
    private static final BigDecimal PER_KILOMETER = new BigDecimal("2.38");
    private static final Duration QUOTE_TTL = Duration.ofMinutes(5);

    private final Clock clock;
    private final RideQuoteRepository quoteRepository;
    private final RideOrderRepository orderRepository;
    private final RideOutboxService outboxService;
    private final RideMessagingProperties messagingProperties;
    private final TransactionTemplate transactionTemplate;

    public RideService(
            Clock clock,
            RideQuoteRepository quoteRepository,
            RideOrderRepository orderRepository,
            RideOutboxService outboxService,
            RideMessagingProperties messagingProperties,
            PlatformTransactionManager transactionManager
    ) {
        this.clock = clock;
        this.quoteRepository = quoteRepository;
        this.orderRepository = orderRepository;
        this.outboxService = outboxService;
        this.messagingProperties = messagingProperties;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Transactional
    public List<RideQuote> quote(QuoteRequest request) {
        Instant expiresAt = clock.instant().plus(QUOTE_TTL);

        List<RideQuote> quotes = Arrays.stream(VehicleType.values())
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
                    return quote;
                })
                .toList();

        quoteRepository.saveAll(quotes.stream().map(RideQuoteEntity::from).toList());
        return quotes;
    }

    public RideOrder createRide(
            String userId,
            String idempotencyKey,
            CreateRideRequest request
    ) {
        String normalizedUserId = requireText(userId, "用户 ID", 128);
        String normalizedKey = requireText(idempotencyKey, "Idempotency-Key", 128);

        RideOrder existing = orderRepository
                .findByUserIdAndIdempotencyKey(normalizedUserId, normalizedKey)
                .map(RideOrderEntity::toDomain)
                .orElse(null);
        if (existing != null) {
            return existing;
        }

        try {
            return Objects.requireNonNull(transactionTemplate.execute(status -> {
                RideOrderEntity concurrentExisting = orderRepository
                        .findByUserIdAndIdempotencyKey(normalizedUserId, normalizedKey)
                        .orElse(null);
                if (concurrentExisting != null) {
                    return concurrentExisting.toDomain();
                }

                RideQuote quote = requireValidQuote(request.quoteId());
                RideOrderEntity order = new RideOrderEntity(
                        "XIXI-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(),
                        normalizedUserId,
                        normalizedKey,
                        quote,
                        requireText(request.origin(), "出发地", 255),
                        requireText(request.destination(), "目的地", 255),
                        clock.instant()
                );
                RideOrderEntity saved = orderRepository.saveAndFlush(order);
                outboxService.append(
                        saved,
                        RideEventType.ORDER_CREATED,
                        messagingProperties.getDispatchDelayLevel()
                );
                outboxService.append(
                        saved,
                        RideEventType.ORDER_TIMEOUT_CHECK,
                        messagingProperties.getTimeoutDelayLevel()
                );
                return saved.toDomain();
            }));
        } catch (DataIntegrityViolationException exception) {
            return orderRepository
                    .findByUserIdAndIdempotencyKey(normalizedUserId, normalizedKey)
                    .map(RideOrderEntity::toDomain)
                    .orElseThrow(() -> exception);
        }
    }

    @Transactional(readOnly = true)
    public RideOrder getRide(String userId, String orderId) {
        return orderRepository.findByOrderIdAndUserId(orderId, userId)
                .map(RideOrderEntity::toDomain)
                .orElseThrow(() -> new RideNotFoundException(orderId));
    }

    @Transactional
    public RideOrder cancel(String userId, String orderId) {
        RideOrderEntity order = orderRepository.findOwnedForUpdate(orderId, userId)
                .orElseThrow(() -> new RideNotFoundException(orderId));
        order.transitionTo(RideStatus.CANCELLED);
        outboxService.append(order, RideEventType.ORDER_CANCELLED, 0);
        return order.toDomain();
    }

    @Transactional
    public RideOrder transition(String orderId, RideStatus status) {
        RideOrderEntity order = orderRepository.findForUpdate(orderId)
                .orElseThrow(() -> new RideNotFoundException(orderId));
        order.transitionTo(status);
        outboxService.append(order, eventTypeFor(status), 0);
        return order.toDomain();
    }

    private RideEventType eventTypeFor(RideStatus status) {
        return switch (status) {
            case DRIVER_ASSIGNED -> RideEventType.DRIVER_ASSIGNED;
            case COMPLETED -> RideEventType.RIDE_COMPLETED;
            case CANCELLED -> RideEventType.ORDER_CANCELLED;
            default -> RideEventType.RIDE_STATUS_CHANGED;
        };
    }

    private RideQuote requireValidQuote(String quoteId) {
        RideQuote quote = quoteRepository.findById(quoteId)
                .map(RideQuoteEntity::toDomain)
                .orElseThrow(() -> new IllegalArgumentException("报价不存在：" + quoteId));
        if (quote.isExpired(clock.instant())) {
            throw new IllegalArgumentException("报价已过期，请重新询价");
        }
        return quote;
    }

    private String requireText(String value, String fieldName, int maxLength) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("缺少" + fieldName);
        }
        String normalized = value.trim();
        if (normalized.length() > maxLength) {
            throw new IllegalArgumentException(fieldName + "长度不能超过 " + maxLength + " 个字符");
        }
        return normalized;
    }
}
