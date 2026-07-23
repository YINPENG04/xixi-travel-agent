package cn.xixitravel.ride.domain;

import java.math.BigDecimal;

public enum VehicleType {
    ECONOMY("轻享", 4, new BigDecimal("1.00")),
    COMFORT("舒适", 4, new BigDecimal("1.38")),
    SIX_SEAT("六座", 6, new BigDecimal("1.81"));

    private final String displayName;
    private final int seats;
    private final BigDecimal priceMultiplier;

    VehicleType(String displayName, int seats, BigDecimal priceMultiplier) {
        this.displayName = displayName;
        this.seats = seats;
        this.priceMultiplier = priceMultiplier;
    }

    public String displayName() {
        return displayName;
    }

    public int seats() {
        return seats;
    }

    public BigDecimal priceMultiplier() {
        return priceMultiplier;
    }
}
