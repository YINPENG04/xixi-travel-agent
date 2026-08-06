package cn.xixitravel.ride.confirmation;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;

@Entity
@Table(name = "ride_action_confirmations")
public class RideActionConfirmationEntity {
    @Id
    @Column(name = "token_hash", length = 64, nullable = false)
    private String tokenHash;

    @Column(name = "user_id", length = 128, nullable = false)
    private String userId;

    @Column(name = "conversation_id", length = 128, nullable = false)
    private String conversationId;

    @Enumerated(EnumType.STRING)
    @Column(name = "action_type", length = 32, nullable = false)
    private RideActionType actionType;

    @Column(name = "resource_id", length = 64, nullable = false)
    private String resourceId;

    @Column(name = "request_fingerprint", length = 64, nullable = false)
    private String requestFingerprint;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "consumed_at")
    private Instant consumedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Version
    @Column(name = "lock_version", nullable = false)
    private long lockVersion;

    protected RideActionConfirmationEntity() {
    }

    public RideActionConfirmationEntity(
            String tokenHash,
            String userId,
            String conversationId,
            RideActionType actionType,
            String resourceId,
            String requestFingerprint,
            Instant expiresAt,
            Instant createdAt
    ) {
        this.tokenHash = tokenHash;
        this.userId = userId;
        this.conversationId = conversationId;
        this.actionType = actionType;
        this.resourceId = resourceId;
        this.requestFingerprint = requestFingerprint;
        this.expiresAt = expiresAt;
        this.createdAt = createdAt;
    }

    public void consume(
            String expectedUserId,
            String expectedConversationId,
            RideActionType expectedAction,
            String expectedResourceId,
            String expectedFingerprint,
            Instant now
    ) {
        if (consumedAt != null) {
            throw new RideConfirmationException("确认凭证已经使用，请重新确认");
        }
        if (!expiresAt.isAfter(now)) {
            throw new RideConfirmationException("确认凭证已过期，请重新确认");
        }
        if (!userId.equals(expectedUserId)
                || !conversationId.equals(expectedConversationId)
                || actionType != expectedAction
                || !resourceId.equals(expectedResourceId)
                || !requestFingerprint.equals(expectedFingerprint)) {
            throw new RideConfirmationException("确认凭证与当前操作不匹配");
        }
        consumedAt = now;
    }
}
