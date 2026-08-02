package cn.xixitravel.ride.persistence;

import cn.xixitravel.ride.domain.RideOrder;
import cn.xixitravel.ride.domain.RideQuote;
import cn.xixitravel.ride.domain.RideStatus;
import cn.xixitravel.ride.domain.VehicleType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(
        name = "ride_orders",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_ride_orders_user_idempotency",
                columnNames = {"user_id", "idempotency_key"}
        )
)
public class RideOrderEntity {
    @Id
    @Column(name = "order_id", length = 32, nullable = false)
    private String orderId;

    @Column(name = "user_id", length = 128, nullable = false)
    private String userId;

    @Column(name = "idempotency_key", length = 128, nullable = false)
    private String idempotencyKey;

    @Column(name = "quote_id", length = 32, nullable = false)
    private String quoteId;

    @Column(length = 255, nullable = false)
    private String origin;

    @Column(length = 255, nullable = false)
    private String destination;

    @Enumerated(EnumType.STRING)
    @Column(name = "vehicle_type", length = 32, nullable = false)
    private VehicleType vehicleType;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal price;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Enumerated(EnumType.STRING)
    @Column(length = 32, nullable = false)
    private RideStatus status;

    @Version
    @Column(name = "lock_version", nullable = false)
    private long lockVersion;

    protected RideOrderEntity() {
    }

    public RideOrderEntity(
            String orderId,
            String userId,
            String idempotencyKey,
            RideQuote quote,
            String origin,
            String destination,
            Instant createdAt
    ) {
        this.orderId = orderId;
        this.userId = userId;
        this.idempotencyKey = idempotencyKey;
        this.quoteId = quote.quoteId();
        this.origin = origin;
        this.destination = destination;
        this.vehicleType = quote.vehicleType();
        this.price = quote.price();
        this.createdAt = createdAt;
        this.status = RideStatus.CREATED;
    }

    public void transitionTo(RideStatus next) {
        if (!status.canTransitionTo(next)) {
            throw new IllegalStateException("订单不能从 %s 变更为 %s".formatted(status, next));
        }
        status = next;
    }

    public RideOrder toDomain() {
        return new RideOrder(
                orderId,
                userId,
                quoteId,
                origin,
                destination,
                vehicleType,
                price,
                createdAt,
                status
        );
    }
}
