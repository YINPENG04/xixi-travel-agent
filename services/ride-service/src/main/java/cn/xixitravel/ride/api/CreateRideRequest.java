package cn.xixitravel.ride.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateRideRequest(
        @NotBlank @Size(max = 32) String quoteId,
        @NotBlank @Size(max = 255) String origin,
        @NotBlank @Size(max = 255) String destination
) {
}
