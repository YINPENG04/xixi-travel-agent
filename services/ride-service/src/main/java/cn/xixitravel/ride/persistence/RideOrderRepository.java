package cn.xixitravel.ride.persistence;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface RideOrderRepository extends JpaRepository<RideOrderEntity, String> {
    Optional<RideOrderEntity> findByOrderIdAndUserId(String orderId, String userId);

    Optional<RideOrderEntity> findByUserIdAndIdempotencyKey(
            String userId,
            String idempotencyKey
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select ride from RideOrderEntity ride "
            + "where ride.orderId = :orderId and ride.userId = :userId")
    Optional<RideOrderEntity> findOwnedForUpdate(
            @Param("orderId") String orderId,
            @Param("userId") String userId
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select ride from RideOrderEntity ride where ride.orderId = :orderId")
    Optional<RideOrderEntity> findForUpdate(@Param("orderId") String orderId);
}
