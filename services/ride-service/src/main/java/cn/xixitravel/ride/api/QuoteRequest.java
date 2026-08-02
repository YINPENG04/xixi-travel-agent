package cn.xixitravel.ride.api;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record QuoteRequest(
        @NotBlank @Size(max = 255) String origin,
        @NotBlank @Size(max = 255) String destination,
        @NotNull @DecimalMin("0.1") @DecimalMax("300.0") Double distanceKilometers,
        @NotNull @DecimalMin("1") @DecimalMax("720") Integer durationMinutes
) {
}
