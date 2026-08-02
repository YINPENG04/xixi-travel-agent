package cn.xixitravel.ride.messaging;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface RideInvoiceEligibilityRepository
        extends JpaRepository<RideInvoiceEligibilityEntity, String> {
    Optional<RideInvoiceEligibilityEntity> findByOrderId(String orderId);

    Optional<RideInvoiceEligibilityEntity> findByOrderIdAndUserId(String orderId, String userId);
}
