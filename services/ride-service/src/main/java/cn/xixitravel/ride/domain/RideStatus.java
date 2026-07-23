package cn.xixitravel.ride.domain;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

public enum RideStatus {
    CREATED,
    DRIVER_ASSIGNED,
    DRIVER_ARRIVING,
    DRIVER_ARRIVED,
    IN_PROGRESS,
    COMPLETED,
    CANCELLED;

    private static final Map<RideStatus, Set<RideStatus>> TRANSITIONS = Map.of(
            CREATED, EnumSet.of(DRIVER_ASSIGNED, CANCELLED),
            DRIVER_ASSIGNED, EnumSet.of(DRIVER_ARRIVING, CANCELLED),
            DRIVER_ARRIVING, EnumSet.of(DRIVER_ARRIVED, CANCELLED),
            DRIVER_ARRIVED, EnumSet.of(IN_PROGRESS, CANCELLED),
            IN_PROGRESS, EnumSet.of(COMPLETED),
            COMPLETED, EnumSet.noneOf(RideStatus.class),
            CANCELLED, EnumSet.noneOf(RideStatus.class)
    );

    public boolean canTransitionTo(RideStatus next) {
        return TRANSITIONS.get(this).contains(next);
    }
}
