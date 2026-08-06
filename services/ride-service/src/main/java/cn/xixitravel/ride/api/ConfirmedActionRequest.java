package cn.xixitravel.ride.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ConfirmedActionRequest(
        @NotBlank @Size(max = 128) String conversationId,
        @NotBlank @Size(max = 128) String confirmationToken
) {
}
