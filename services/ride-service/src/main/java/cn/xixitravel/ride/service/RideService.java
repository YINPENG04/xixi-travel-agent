package cn.xixitravel.ride.service;

import cn.xixitravel.ride.api.CreateRideRequest;
import cn.xixitravel.ride.api.QuoteRequest;
import cn.xixitravel.ride.cache.RideCacheService;
import cn.xixitravel.ride.confirmation.RideActionConfirmationService;
import cn.xixitravel.ride.confirmation.RideActionType;
import cn.xixitravel.ride.confirmation.RideConfirmationChallenge;
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
    private final RideCacheService cacheService;
    private final RideActionConfirmationService confirmationService;
    private final TransactionTemplate transactionTemplate;

    public RideService(
            Clock clock,
            RideQuoteRepository quoteRepository,
            RideOrderRepository orderRepository,
            RideOutboxService outboxService,
            RideMessagingProperties messagingProperties,
            RideCacheService cacheService,
            RideActionConfirmationService confirmationService,
            PlatformTransactionManager transactionManager
    ) {
        this.clock = clock;
        this.quoteRepository = quoteRepository;
        this.orderRepository = orderRepository;
        this.outboxService = outboxService;
        this.messagingProperties = messagingProperties;
        this.cacheService = cacheService;
        this.confirmationService = confirmationService;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Transactional
    public List<RideQuote> quote(QuoteRequest request) {
        Instant expiresAt = clock.instant().plus(QUOTE_TTL);
        String origin = requireText(request.origin(), "出发地", 255);
        String destination = requireText(request.destination(), "目的地", 255);

        List<RideQuote> quotes = Arrays.stream(VehicleType.values())
                .map(type -> {
                    BigDecimal base = BASE_FARE.add(
                            PER_KILOMETER.multiply(BigDecimal.valueOf(request.distanceKilometers()))
                    );
                    BigDecimal price = base.multiply(type.priceMultiplier())
                            .setScale(0, RoundingMode.HALF_UP);
                    RideQuote quote = new RideQuote(
                            "Q-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(),
                            origin,
                            destination,
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
        quotes.forEach(cacheService::putQuoteAfterCommit);
        return quotes;
    }

    public RideConfirmationChallenge prepareCreate(
            String userId,
            String conversationId,
            String quoteId,
            String origin,
            String destination
    ) {
        String normalizedUserId = requireText(userId, "用户 ID", 128);
        String normalizedConversationId = requireText(conversationId, "会话 ID", 128);
        String normalizedOrigin = requireText(origin, "出发地", 255);
        String normalizedDestination = requireText(destination, "目的地", 255);
        RideQuote quote = requireValidQuote(quoteId);
        requireMatchingRoute(quote, normalizedOrigin, normalizedDestination);
        return confirmationService.issue(
                normalizedUserId,
                normalizedConversationId,
                RideActionType.CREATE_RIDE,
                quote.quoteId(),
                createFingerprint(quote.quoteId(), normalizedOrigin, normalizedDestination),
                quote.expiresAt()
        );
    }

    public RideConfirmationChallenge prepareCancel(
            String userId,
            String conversationId,
            String orderId
    ) {
        String normalizedUserId = requireText(userId, "用户 ID", 128);
        String normalizedConversationId = requireText(conversationId, "会话 ID", 128);
        String normalizedOrderId = requireText(orderId, "订单 ID", 32);
        RideOrder order = getRide(normalizedUserId, normalizedOrderId);
        if (!order.getStatus().canTransitionTo(RideStatus.CANCELLED)) {
            throw new IllegalStateException("当前订单状态不允许取消");
        }
        return confirmationService.issue(
                normalizedUserId,
                normalizedConversationId,
                RideActionType.CANCEL_RIDE,
                normalizedOrderId,
                cancelFingerprint(normalizedOrderId),
                null
        );
    }

    public RideOrder createRide(
            String userId,
            String idempotencyKey,
            CreateRideRequest request
    ) {
        String normalizedUserId = requireText(userId, "用户 ID", 128);
        String normalizedKey = requireText(idempotencyKey, "Idempotency-Key", 128);
        String normalizedConversationId = requireText(request.conversationId(), "会话 ID", 128);
        String normalizedQuoteId = requireText(request.quoteId(), "报价 ID", 32);
        String normalizedOrigin = requireText(request.origin(), "出发地", 255);
        String normalizedDestination = requireText(request.destination(), "目的地", 255);

        RideOrder existing = orderRepository
                .findByUserIdAndIdempotencyKey(normalizedUserId, normalizedKey)
                .map(RideOrderEntity::toDomain)
                .orElse(null);
        if (existing != null) {
            requireSameIdempotentRequest(
                    existing,
                    normalizedQuoteId,
                    normalizedOrigin,
                    normalizedDestination
            );
            cacheService.putOrder(existing);
            return existing;
        }

        try {
            RideOrder created = Objects.requireNonNull(transactionTemplate.execute(status -> {
                RideOrderEntity concurrentExisting = orderRepository
                        .findByUserIdAndIdempotencyKey(normalizedUserId, normalizedKey)
                        .orElse(null);
                if (concurrentExisting != null) {
                    RideOrder concurrentOrder = concurrentExisting.toDomain();
                    requireSameIdempotentRequest(
                            concurrentOrder,
                            normalizedQuoteId,
                            normalizedOrigin,
                            normalizedDestination
                    );
                    return concurrentOrder;
                }

                RideQuote quote = requireValidQuote(normalizedQuoteId);
                requireMatchingRoute(quote, normalizedOrigin, normalizedDestination);
                confirmationService.consume(
                        request.confirmationToken(),
                        normalizedUserId,
                        normalizedConversationId,
                        RideActionType.CREATE_RIDE,
                        quote.quoteId(),
                        createFingerprint(
                                quote.quoteId(),
                                normalizedOrigin,
                                normalizedDestination
                        )
                );
                RideOrderEntity order = new RideOrderEntity(
                        "XIXI-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(),
                        normalizedUserId,
                        normalizedKey,
                        quote,
                        quote.origin(),
                        quote.destination(),
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
            cacheService.putOrder(created);
            return created;
        } catch (DataIntegrityViolationException exception) {
            RideOrder concurrent = orderRepository
                    .findByUserIdAndIdempotencyKey(normalizedUserId, normalizedKey)
                    .map(RideOrderEntity::toDomain)
                    .orElseThrow(() -> exception);
            requireSameIdempotentRequest(
                    concurrent,
                    normalizedQuoteId,
                    normalizedOrigin,
                    normalizedDestination
            );
            cacheService.putOrder(concurrent);
            return concurrent;
        }
    }

    @Transactional(readOnly = true)
    public RideOrder getRide(String userId, String orderId) {
        RideOrder cached = cacheService.getOrder(userId, orderId).orElse(null);
        if (cached != null) {
            return cached;
        }
        RideOrder order = orderRepository.findByOrderIdAndUserId(orderId, userId)
                .map(RideOrderEntity::toDomain)
                .orElseThrow(() -> new RideNotFoundException(orderId));
        cacheService.putOrderAfterCommit(order);
        return order;
    }

    @Transactional
    public RideOrder cancel(
            String userId,
            String conversationId,
            String confirmationToken,
            String orderId
    ) {
        String normalizedUserId = requireText(userId, "用户 ID", 128);
        String normalizedConversationId = requireText(conversationId, "会话 ID", 128);
        String normalizedOrderId = requireText(orderId, "订单 ID", 32);
        confirmationService.consume(
                confirmationToken,
                normalizedUserId,
                normalizedConversationId,
                RideActionType.CANCEL_RIDE,
                normalizedOrderId,
                cancelFingerprint(normalizedOrderId)
        );
        RideOrderEntity order = orderRepository.findOwnedForUpdate(
                        normalizedOrderId,
                        normalizedUserId
                )
                .orElseThrow(() -> new RideNotFoundException(normalizedOrderId));
        order.transitionTo(RideStatus.CANCELLED);
        outboxService.append(order, RideEventType.ORDER_CANCELLED, 0);
        RideOrder result = order.toDomain();
        cacheService.putOrderAfterCommit(result);
        return result;
    }

    @Transactional
    public RideOrder transition(String orderId, RideStatus status) {
        RideOrderEntity order = orderRepository.findForUpdate(orderId)
                .orElseThrow(() -> new RideNotFoundException(orderId));
        order.transitionTo(status);
        outboxService.append(order, eventTypeFor(status), 0);
        RideOrder result = order.toDomain();
        cacheService.putOrderAfterCommit(result);
        return result;
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
        RideQuote quote = cacheService.getQuote(quoteId).orElseGet(() -> {
            RideQuote loaded = quoteRepository.findById(quoteId)
                    .map(RideQuoteEntity::toDomain)
                    .orElseThrow(() -> new IllegalArgumentException("报价不存在：" + quoteId));
            cacheService.putQuoteAfterCommit(loaded);
            return loaded;
        });
        if (quote.isExpired(clock.instant())) {
            throw new IllegalArgumentException("报价已过期，请重新询价");
        }
        return quote;
    }

    private void requireMatchingRoute(
            RideQuote quote,
            String origin,
            String destination
    ) {
        if (!quote.origin().equals(origin) || !quote.destination().equals(destination)) {
            throw new IllegalArgumentException("下单路线与报价快照不一致，请重新询价");
        }
    }

    private void requireSameIdempotentRequest(
            RideOrder existing,
            String quoteId,
            String origin,
            String destination
    ) {
        if (!existing.getQuoteId().equals(quoteId)
                || !existing.getOrigin().equals(origin)
                || !existing.getDestination().equals(destination)) {
            throw new IdempotencyConflictException();
        }
    }

    private String createFingerprint(String quoteId, String origin, String destination) {
        return confirmationService.fingerprint("CREATE_RIDE", quoteId, origin, destination);
    }

    private String cancelFingerprint(String orderId) {
        return confirmationService.fingerprint("CANCEL_RIDE", orderId);
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
