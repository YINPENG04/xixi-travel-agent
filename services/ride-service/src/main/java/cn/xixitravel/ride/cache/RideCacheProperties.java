package cn.xixitravel.ride.cache;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
@ConfigurationProperties(prefix = "xixi.cache")
public class RideCacheProperties {
    private boolean enabled;
    private Duration orderTtl = Duration.ofMinutes(2);
    private Duration memoryTtl = Duration.ofMinutes(30);
    private Duration memorySearchTtl = Duration.ofMinutes(10);
    private Duration sessionContextTtl = Duration.ofMinutes(30);
    private Duration reactRagTtl = Duration.ofMinutes(10);
    private Duration reactRagLockTtl = Duration.ofSeconds(30);

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Duration getOrderTtl() {
        return orderTtl;
    }

    public void setOrderTtl(Duration orderTtl) {
        this.orderTtl = orderTtl;
    }

    public Duration getMemoryTtl() {
        return memoryTtl;
    }

    public void setMemoryTtl(Duration memoryTtl) {
        this.memoryTtl = memoryTtl;
    }

    public Duration getMemorySearchTtl() {
        return memorySearchTtl;
    }

    public void setMemorySearchTtl(Duration memorySearchTtl) {
        this.memorySearchTtl = memorySearchTtl;
    }

    public Duration getSessionContextTtl() {
        return sessionContextTtl;
    }

    public void setSessionContextTtl(Duration sessionContextTtl) {
        this.sessionContextTtl = sessionContextTtl;
    }

    public Duration getReactRagTtl() {
        return reactRagTtl;
    }

    public void setReactRagTtl(Duration reactRagTtl) {
        this.reactRagTtl = reactRagTtl;
    }

    public Duration getReactRagLockTtl() {
        return reactRagLockTtl;
    }

    public void setReactRagLockTtl(Duration reactRagLockTtl) {
        this.reactRagLockTtl = reactRagLockTtl;
    }
}
