package cn.xixitravel.ride.api;

import jakarta.validation.constraints.NotBlank;

public record CreateRideRequest(
        @NotBlank String quoteId,
        @NotBlank String origin,
        @NotBlank String destination
) {
}
