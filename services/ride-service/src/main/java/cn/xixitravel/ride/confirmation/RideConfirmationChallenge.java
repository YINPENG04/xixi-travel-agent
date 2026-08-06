package cn.xixitravel.ride.confirmation;

import java.time.Instant;

public record RideConfirmationChallenge(
        String confirmationToken,
        RideActionType action,
        String resourceId,
        Instant expiresAt
) {
}
