package cn.xixitravel.ride.api;

import cn.xixitravel.ride.domain.RideOrder;
import cn.xixitravel.ride.domain.RideQuote;
import cn.xixitravel.ride.domain.RideStatus;
import cn.xixitravel.ride.messaging.RideAsyncQueryService;
import cn.xixitravel.ride.messaging.RideInvoiceEligibility;
import cn.xixitravel.ride.messaging.RideNotification;
import cn.xixitravel.ride.service.RideService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1")
public class RideController {
    private final RideService rideService;
    private final RideAsyncQueryService asyncQueryService;

    public RideController(RideService rideService, RideAsyncQueryService asyncQueryService) {
        this.rideService = rideService;
        this.asyncQueryService = asyncQueryService;
    }

    @PostMapping("/quotes")
    public List<RideQuote> quote(@Valid @RequestBody QuoteRequest request) {
        return rideService.quote(request);
    }

    @PostMapping("/rides")
    @ResponseStatus(HttpStatus.CREATED)
    public RideOrder createRide(
            @RequestHeader(value = "X-Xixi-User", defaultValue = "demo-user") String userId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody CreateRideRequest request
    ) {
        return rideService.createRide(userId, idempotencyKey, request);
    }

    @GetMapping("/rides/{orderId}")
    public RideOrder getRide(
            @RequestHeader(value = "X-Xixi-User", defaultValue = "demo-user") String userId,
            @PathVariable String orderId
    ) {
        return rideService.getRide(userId, orderId);
    }

    @GetMapping("/rides/{orderId}/notifications")
    public List<RideNotification> notifications(
            @RequestHeader(value = "X-Xixi-User", defaultValue = "demo-user") String userId,
            @PathVariable String orderId
    ) {
        return asyncQueryService.notifications(userId, orderId);
    }

    @GetMapping("/rides/{orderId}/invoice-eligibility")
    public RideInvoiceEligibility invoiceEligibility(
            @RequestHeader(value = "X-Xixi-User", defaultValue = "demo-user") String userId,
            @PathVariable String orderId
    ) {
        return asyncQueryService.invoiceEligibility(userId, orderId);
    }

    @PostMapping("/rides/{orderId}/cancel")
    public RideOrder cancelRide(
            @RequestHeader(value = "X-Xixi-User", defaultValue = "demo-user") String userId,
            @PathVariable String orderId
    ) {
        return rideService.cancel(userId, orderId);
    }

    @PatchMapping("/internal/rides/{orderId}/status/{status}")
    public RideOrder transition(
            @PathVariable String orderId,
            @PathVariable RideStatus status
    ) {
        return rideService.transition(orderId, status);
    }
}
