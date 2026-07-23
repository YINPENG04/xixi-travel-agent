package cn.xixitravel.ride.domain;

import java.math.BigDecimal;
import java.time.Instant;

public record RideQuote(
        String quoteId,
        VehicleType vehicleType,
        String vehicleName,
        int seats,
        BigDecimal price,
        int etaMinutes,
        double distanceKilometers,
        int durationMinutes,
        Instant expiresAt
) {
    public boolean isExpired(Instant now) {
        return !expiresAt.isAfter(now);
    }
}
