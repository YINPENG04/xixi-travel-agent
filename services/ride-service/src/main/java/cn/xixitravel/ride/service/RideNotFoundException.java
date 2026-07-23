package cn.xixitravel.ride.service;

public class RideNotFoundException extends RuntimeException {
    public RideNotFoundException(String orderId) {
        super("未找到行程：" + orderId);
    }
}
