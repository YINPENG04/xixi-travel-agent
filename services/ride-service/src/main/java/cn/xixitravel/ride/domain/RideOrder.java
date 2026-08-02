package cn.xixitravel.ride.domain;

import java.math.BigDecimal;
import java.time.Instant;

public final class RideOrder {
    private final String orderId;
    private final String userId;
    private final String quoteId;
    private final String origin;
    private final String destination;
    private final VehicleType vehicleType;
    private final BigDecimal price;
    private final Instant createdAt;
    private RideStatus status;

    public RideOrder(
            String orderId,
            String userId,
            String quoteId,
            String origin,
            String destination,
            VehicleType vehicleType,
            BigDecimal price,
            Instant createdAt
    ) {
        this(
                orderId,
                userId,
                quoteId,
                origin,
                destination,
                vehicleType,
                price,
                createdAt,
                RideStatus.CREATED
        );
    }

    public RideOrder(
            String orderId,
            String userId,
            String quoteId,
            String origin,
            String destination,
            VehicleType vehicleType,
            BigDecimal price,
            Instant createdAt,
            RideStatus status
    ) {
        this.orderId = orderId;
        this.userId = userId;
        this.quoteId = quoteId;
        this.origin = origin;
        this.destination = destination;
        this.vehicleType = vehicleType;
        this.price = price;
        this.createdAt = createdAt;
        this.status = status;
    }

    public synchronized RideOrder transitionTo(RideStatus next) {
        if (!status.canTransitionTo(next)) {
            throw new IllegalStateException("订单不能从 %s 变更为 %s".formatted(status, next));
        }
        status = next;
        return this;
    }

    public String getOrderId() {
        return orderId;
    }

    public String getUserId() {
        return userId;
    }

    public String getQuoteId() {
        return quoteId;
    }

    public String getOrigin() {
        return origin;
    }

    public String getDestination() {
        return destination;
    }

    public VehicleType getVehicleType() {
        return vehicleType;
    }

    public BigDecimal getPrice() {
        return price;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public RideStatus getStatus() {
        return status;
    }
}
