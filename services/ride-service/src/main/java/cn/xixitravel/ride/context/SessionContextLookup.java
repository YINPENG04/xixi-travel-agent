package cn.xixitravel.ride.context;

public record SessionContextLookup(
        boolean found,
        SessionContextState context
) {
}
