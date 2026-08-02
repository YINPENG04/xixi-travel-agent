package cn.xixitravel.ride.messaging;

import cn.xixitravel.ride.persistence.RideOrderRepository;
import cn.xixitravel.ride.service.RideNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class RideAsyncQueryService {
    private final RideOrderRepository orderRepository;
    private final RideNotificationRepository notificationRepository;
    private final RideInvoiceEligibilityRepository invoiceRepository;

    public RideAsyncQueryService(
            RideOrderRepository orderRepository,
            RideNotificationRepository notificationRepository,
            RideInvoiceEligibilityRepository invoiceRepository
    ) {
        this.orderRepository = orderRepository;
        this.notificationRepository = notificationRepository;
        this.invoiceRepository = invoiceRepository;
    }

    @Transactional(readOnly = true)
    public List<RideNotification> notifications(String userId, String orderId) {
        requireOwnedOrder(userId, orderId);
        return notificationRepository.findByUserIdAndOrderIdOrderByCreatedAt(userId, orderId)
                .stream()
                .map(RideNotificationEntity::toView)
                .toList();
    }

    @Transactional(readOnly = true)
    public RideInvoiceEligibility invoiceEligibility(String userId, String orderId) {
        requireOwnedOrder(userId, orderId);
        return invoiceRepository.findByOrderIdAndUserId(orderId, userId)
                .map(RideInvoiceEligibilityEntity::toView)
                .orElseGet(() -> RideInvoiceEligibility.unavailable(orderId));
    }

    private void requireOwnedOrder(String userId, String orderId) {
        orderRepository.findByOrderIdAndUserId(orderId, userId)
                .orElseThrow(() -> new RideNotFoundException(orderId));
    }
}
