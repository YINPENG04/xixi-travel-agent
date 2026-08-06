package cn.xixitravel.ride.service;

public class IdempotencyConflictException extends RuntimeException {
    public IdempotencyConflictException() {
        super("同一幂等键不能用于不同的下单请求");
    }
}
