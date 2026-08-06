package cn.xixitravel.ride.confirmation;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;

@Service
public class RideActionConfirmationService {
    private static final Duration CONFIRMATION_TTL = Duration.ofMinutes(5);
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final RideActionConfirmationRepository repository;
    private final Clock clock;

    public RideActionConfirmationService(
            RideActionConfirmationRepository repository,
            Clock clock
    ) {
        this.repository = repository;
        this.clock = clock;
    }

    @Transactional
    public RideConfirmationChallenge issue(
            String userId,
            String conversationId,
            RideActionType action,
            String resourceId,
            String requestFingerprint,
            Instant maximumExpiry
    ) {
        Instant now = clock.instant();
        Instant expiresAt = now.plus(CONFIRMATION_TTL);
        if (maximumExpiry != null && maximumExpiry.isBefore(expiresAt)) {
            expiresAt = maximumExpiry;
        }
        if (!expiresAt.isAfter(now)) {
            throw new RideConfirmationException("操作已失效，请重新发起");
        }

        byte[] randomBytes = new byte[32];
        SECURE_RANDOM.nextBytes(randomBytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);
        repository.save(new RideActionConfirmationEntity(
                sha256(token),
                userId,
                conversationId,
                action,
                resourceId,
                requestFingerprint,
                expiresAt,
                now
        ));
        return new RideConfirmationChallenge(token, action, resourceId, expiresAt);
    }

    @Transactional
    public void consume(
            String token,
            String userId,
            String conversationId,
            RideActionType action,
            String resourceId,
            String requestFingerprint
    ) {
        if (token == null || token.isBlank() || token.length() > 128) {
            throw new RideConfirmationException("缺少有效的确认凭证");
        }
        RideActionConfirmationEntity confirmation = repository
                .findForUpdate(sha256(token.trim()))
                .orElseThrow(() -> new RideConfirmationException("确认凭证不存在或已失效"));
        confirmation.consume(
                userId,
                conversationId,
                action,
                resourceId,
                requestFingerprint,
                clock.instant()
        );
    }

    public String fingerprint(String... values) {
        return sha256(String.join("\u001f", values));
    }

    private String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
