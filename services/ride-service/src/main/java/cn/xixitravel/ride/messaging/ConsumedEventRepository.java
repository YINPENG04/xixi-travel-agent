package cn.xixitravel.ride.messaging;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ConsumedEventRepository extends JpaRepository<ConsumedEventEntity, String> {
}
