package cn.xixitravel.ride.messaging;

import org.apache.rocketmq.spring.annotation.ConsumeMode;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "xixi.messaging", name = "enabled", havingValue = "true")
@RocketMQMessageListener(
        topic = "${xixi.messaging.topic:xixi-ride-events}",
        consumerGroup = RideEventProcessor.NOTIFICATION_CONSUMER,
        selectorExpression = "DRIVER_ASSIGNED || ORDER_CANCELLED || RIDE_COMPLETED",
        consumeMode = ConsumeMode.ORDERLY,
        maxReconsumeTimes = 5
)
public class RideNotificationListener implements RocketMQListener<String> {
    private final RideEventCodec eventCodec;
    private final RideEventProcessor eventProcessor;

    public RideNotificationListener(RideEventCodec eventCodec, RideEventProcessor eventProcessor) {
        this.eventCodec = eventCodec;
        this.eventProcessor = eventProcessor;
    }

    @Override
    public void onMessage(String payload) {
        eventProcessor.processNotificationEvent(eventCodec.decode(payload));
    }
}
