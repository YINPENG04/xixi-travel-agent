package cn.xixitravel.ride.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ConversationRequest(
        @NotBlank @Size(max = 128) String conversationId
) {
}
