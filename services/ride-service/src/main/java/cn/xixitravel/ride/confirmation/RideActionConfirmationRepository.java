package cn.xixitravel.ride.confirmation;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;
import java.util.Optional;

public interface RideActionConfirmationRepository
        extends JpaRepository<RideActionConfirmationEntity, String> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select confirmation from RideActionConfirmationEntity confirmation "
            + "where confirmation.tokenHash = :tokenHash")
    Optional<RideActionConfirmationEntity> findForUpdate(
            @Param("tokenHash") String tokenHash
    );
}
