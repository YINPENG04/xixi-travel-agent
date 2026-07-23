package cn.xixitravel.ride.api;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record QuoteRequest(
        @NotBlank String origin,
        @NotBlank String destination,
        @NotNull @DecimalMin("0.1") @DecimalMax("300.0") Double distanceKilometers,
        @NotNull @DecimalMin("1") @DecimalMax("720") Integer durationMinutes
) {
}
