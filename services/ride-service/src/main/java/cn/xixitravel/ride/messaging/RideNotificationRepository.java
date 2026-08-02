package cn.xixitravel.ride.messaging;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface RideNotificationRepository extends JpaRepository<RideNotificationEntity, String> {
    List<RideNotificationEntity> findByUserIdAndOrderIdOrderByCreatedAt(String userId, String orderId);
}
