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
        consumerGroup = RideEventProcessor.DISPATCH_CONSUMER,
        selectorExpression = "ORDER_CREATED || ORDER_TIMEOUT_CHECK",
        consumeMode = ConsumeMode.ORDERLY,
        maxReconsumeTimes = 5
)
public class RideDispatchListener implements RocketMQListener<String> {
    private final RideEventCodec eventCodec;
    private final RideEventProcessor eventProcessor;

    public RideDispatchListener(RideEventCodec eventCodec, RideEventProcessor eventProcessor) {
        this.eventCodec = eventCodec;
        this.eventProcessor = eventProcessor;
    }

    @Override
    public void onMessage(String payload) {
        eventProcessor.processDispatchEvent(eventCodec.decode(payload));
    }
}
