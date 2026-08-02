package cn.xixitravel.ride.persistence;

import cn.xixitravel.ride.domain.RideQuote;
import cn.xixitravel.ride.domain.VehicleType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "ride_quotes")
public class RideQuoteEntity {
    @Id
    @Column(name = "quote_id", length = 32, nullable = false)
    private String quoteId;

    @Enumerated(EnumType.STRING)
    @Column(name = "vehicle_type", length = 32, nullable = false)
    private VehicleType vehicleType;

    @Column(name = "vehicle_name", length = 32, nullable = false)
    private String vehicleName;

    @Column(nullable = false)
    private int seats;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal price;

    @Column(name = "eta_minutes", nullable = false)
    private int etaMinutes;

    @Column(name = "distance_kilometers", nullable = false)
    private double distanceKilometers;

    @Column(name = "duration_minutes", nullable = false)
    private int durationMinutes;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    protected RideQuoteEntity() {
    }

    private RideQuoteEntity(RideQuote quote) {
        this.quoteId = quote.quoteId();
        this.vehicleType = quote.vehicleType();
        this.vehicleName = quote.vehicleName();
        this.seats = quote.seats();
        this.price = quote.price();
        this.etaMinutes = quote.etaMinutes();
        this.distanceKilometers = quote.distanceKilometers();
        this.durationMinutes = quote.durationMinutes();
        this.expiresAt = quote.expiresAt();
    }

    public static RideQuoteEntity from(RideQuote quote) {
        return new RideQuoteEntity(quote);
    }

    public RideQuote toDomain() {
        return new RideQuote(
                quoteId,
                vehicleType,
                vehicleName,
                seats,
                price,
                etaMinutes,
                distanceKilometers,
                durationMinutes,
                expiresAt
        );
    }
}
