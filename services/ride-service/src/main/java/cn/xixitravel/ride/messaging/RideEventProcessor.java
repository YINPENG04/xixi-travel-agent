package cn.xixitravel.ride.messaging;

import cn.xixitravel.ride.cache.RideCacheService;
import cn.xixitravel.ride.domain.RideStatus;
import cn.xixitravel.ride.persistence.RideOrderEntity;
import cn.xixitravel.ride.persistence.RideOrderRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.UUID;

@Service
public class RideEventProcessor {
    public static final String DISPATCH_CONSUMER = "xixi-dispatch-consumer";
    public static final String NOTIFICATION_CONSUMER = "xixi-notification-consumer";
    public static final String INVOICE_CONSUMER = "xixi-invoice-consumer";

    private final ConsumedEventRepository consumedEventRepository;
    private final RideOrderRepository orderRepository;
    private final RideNotificationRepository notificationRepository;
    private final RideInvoiceEligibilityRepository invoiceRepository;
    private final RideOutboxService outboxService;
    private final RideCacheService cacheService;
    private final Clock clock;

    public RideEventProcessor(
            ConsumedEventRepository consumedEventRepository,
            RideOrderRepository orderRepository,
            RideNotificationRepository notificationRepository,
            RideInvoiceEligibilityRepository invoiceRepository,
            RideOutboxService outboxService,
            RideCacheService cacheService,
            Clock clock
    ) {
        this.consumedEventRepository = consumedEventRepository;
        this.orderRepository = orderRepository;
        this.notificationRepository = notificationRepository;
        this.invoiceRepository = invoiceRepository;
        this.outboxService = outboxService;
        this.cacheService = cacheService;
        this.clock = clock;
    }

    @Transactional
    public void processDispatchEvent(RideEventMessage event) {
        if (!claim(event, DISPATCH_CONSUMER)) {
            return;
        }
        RideOrderEntity order = orderRepository.findForUpdate(event.orderId()).orElse(null);
        if (order == null) {
            return;
        }

        if (event.eventType() == RideEventType.ORDER_CREATED
                && order.getStatus() == RideStatus.CREATED) {
            order.transitionTo(RideStatus.DRIVER_ASSIGNED);
            outboxService.append(order, RideEventType.DRIVER_ASSIGNED, 0);
            cacheService.putOrderAfterCommit(order.toDomain());
        } else if (event.eventType() == RideEventType.ORDER_TIMEOUT_CHECK
                && order.getStatus() == RideStatus.CREATED) {
            order.transitionTo(RideStatus.CANCELLED);
            outboxService.append(order, RideEventType.ORDER_CANCELLED, 0);
            cacheService.putOrderAfterCommit(order.toDomain());
        }
    }

    @Transactional
    public void processNotificationEvent(RideEventMessage event) {
        if (!claim(event, NOTIFICATION_CONSUMER)) {
            return;
        }
        String message = switch (event.eventType()) {
            case DRIVER_ASSIGNED -> "司机已接单，正在前往上车点";
            case ORDER_CANCELLED -> "订单已取消";
            case RIDE_COMPLETED -> "行程已完成，可以申请电子发票";
            default -> null;
        };
        if (message == null) {
            return;
        }
        notificationRepository.save(new RideNotificationEntity(
                UUID.randomUUID().toString(),
                event.eventId(),
                event.orderId(),
                event.userId(),
                event.eventType(),
                message,
                clock.instant()
        ));
    }

    @Transactional
    public void processInvoiceEvent(RideEventMessage event) {
        if (!claim(event, INVOICE_CONSUMER)) {
            return;
        }
        if (event.eventType() != RideEventType.RIDE_COMPLETED
                || invoiceRepository.findByOrderId(event.orderId()).isPresent()) {
            return;
        }
        RideOrderEntity order = orderRepository.findForUpdate(event.orderId()).orElse(null);
        if (order == null || order.getStatus() != RideStatus.COMPLETED) {
            return;
        }
        invoiceRepository.save(new RideInvoiceEligibilityEntity(
                "INV-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(),
                order.getOrderId(),
                order.getUserId(),
                order.getPrice(),
                clock.instant()
        ));
    }

    private boolean claim(RideEventMessage event, String consumerName) {
        String consumerEventId = consumerName + ":" + event.eventId();
        if (consumedEventRepository.existsById(consumerEventId)) {
            return false;
        }
        consumedEventRepository.saveAndFlush(new ConsumedEventEntity(
                event.eventId(),
                consumerName,
                clock.instant()
        ));
        return true;
    }
}
