package cn.xixitravel.ride.messaging;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(
        name = "ride_invoice_eligibility",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_ride_invoice_order",
                columnNames = "order_id"
        )
)
public class RideInvoiceEligibilityEntity {
    @Id
    @Column(name = "invoice_id", length = 36, nullable = false)
    private String invoiceId;

    @Column(name = "order_id", length = 32, nullable = false)
    private String orderId;

    @Column(name = "user_id", length = 128, nullable = false)
    private String userId;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal amount;

    @Column(name = "invoice_status", length = 32, nullable = false)
    private String status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected RideInvoiceEligibilityEntity() {
    }

    public RideInvoiceEligibilityEntity(
            String invoiceId,
            String orderId,
            String userId,
            BigDecimal amount,
            Instant createdAt
    ) {
        this.invoiceId = invoiceId;
        this.orderId = orderId;
        this.userId = userId;
        this.amount = amount;
        this.status = "ELIGIBLE";
        this.createdAt = createdAt;
    }

    public RideInvoiceEligibility toView() {
        return new RideInvoiceEligibility(orderId, true, invoiceId, amount, status, createdAt);
    }
}
