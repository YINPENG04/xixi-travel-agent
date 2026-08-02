package cn.xixitravel.ride.messaging;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface RideOutboxRepository extends JpaRepository<RideOutboxEntity, String> {
    @Query("select event.eventId from RideOutboxEntity event "
            + "where event.status = :status and event.availableAt <= :now "
            + "order by event.createdAt")
    List<String> findPublishableIds(
            @Param("status") OutboxStatus status,
            @Param("now") Instant now,
            Pageable pageable
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select event from RideOutboxEntity event where event.eventId = :eventId")
    Optional<RideOutboxEntity> findForUpdate(@Param("eventId") String eventId);

    List<RideOutboxEntity> findByAggregateIdOrderByCreatedAt(String aggregateId);
}
