package cn.xixitravel.ride.messaging;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "xixi.messaging")
public class RideMessagingProperties {
    private boolean enabled;
    private String topic = "xixi-ride-events";
    private int sendTimeoutMs = 5000;
    private int dispatchDelayLevel = 2;
    private int timeoutDelayLevel = 4;
    private long outboxPollDelayMs = 1000;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getTopic() {
        return topic;
    }

    public void setTopic(String topic) {
        this.topic = topic;
    }

    public int getSendTimeoutMs() {
        return sendTimeoutMs;
    }

    public void setSendTimeoutMs(int sendTimeoutMs) {
        this.sendTimeoutMs = sendTimeoutMs;
    }

    public int getDispatchDelayLevel() {
        return dispatchDelayLevel;
    }

    public void setDispatchDelayLevel(int dispatchDelayLevel) {
        this.dispatchDelayLevel = dispatchDelayLevel;
    }

    public int getTimeoutDelayLevel() {
        return timeoutDelayLevel;
    }

    public void setTimeoutDelayLevel(int timeoutDelayLevel) {
        this.timeoutDelayLevel = timeoutDelayLevel;
    }

    public long getOutboxPollDelayMs() {
        return outboxPollDelayMs;
    }

    public void setOutboxPollDelayMs(long outboxPollDelayMs) {
        this.outboxPollDelayMs = outboxPollDelayMs;
    }
}
