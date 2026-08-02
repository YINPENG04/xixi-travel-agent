package cn.xixitravel.ride.messaging;

import java.math.BigDecimal;
import java.time.Instant;

public record RideInvoiceEligibility(
        String orderId,
        boolean eligible,
        String invoiceId,
        BigDecimal amount,
        String status,
        Instant createdAt
) {
    public static RideInvoiceEligibility unavailable(String orderId) {
        return new RideInvoiceEligibility(orderId, false, null, null, "NOT_AVAILABLE", null);
    }
}
