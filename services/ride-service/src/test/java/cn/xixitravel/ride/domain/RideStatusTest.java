package cn.xixitravel.ride.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RideStatusTest {

    @Test
    void followsTheAllowedOrderLifecycle() {
        RideOrder order = order();

        order.transitionTo(RideStatus.DRIVER_ASSIGNED)
                .transitionTo(RideStatus.DRIVER_ARRIVING)
                .transitionTo(RideStatus.DRIVER_ARRIVED)
                .transitionTo(RideStatus.IN_PROGRESS)
                .transitionTo(RideStatus.COMPLETED);

        assertEquals(RideStatus.COMPLETED, order.getStatus());
    }

    @Test
    void rejectsUnsafeTransition() {
        RideOrder order = order();

        assertThrows(
                IllegalStateException.class,
                () -> order.transitionTo(RideStatus.COMPLETED)
        );
    }

    private RideOrder order() {
        return new RideOrder(
                "XIXI-TEST",
                "user-1",
                "Q-TEST",
                "故宫博物院",
                "北京南站",
                VehicleType.COMFORT,
                new BigDecimal("58"),
                Instant.parse("2026-07-24T00:00:00Z")
        );
    }
}
