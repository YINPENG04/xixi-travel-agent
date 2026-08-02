package cn.xixitravel.ride.messaging;

import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.apache.rocketmq.spring.support.RocketMQHeaders;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "xixi.messaging", name = "enabled", havingValue = "true")
public class RocketMqEventSender {
    private final RocketMQTemplate rocketMQTemplate;
    private final RideMessagingProperties properties;

    public RocketMqEventSender(
            RocketMQTemplate rocketMQTemplate,
            RideMessagingProperties properties
    ) {
        this.rocketMQTemplate = rocketMQTemplate;
        this.properties = properties;
    }

    public void send(RideOutboxEntity event) {
        Message<String> message = MessageBuilder.withPayload(event.getPayload())
                .setHeader(RocketMQHeaders.KEYS, event.getEventId())
                .build();
        String destination = properties.getTopic() + ":" + event.getEventType().name();
        if (event.getDelayLevel() > 0) {
            rocketMQTemplate.syncSend(
                    destination,
                    message,
                    properties.getSendTimeoutMs(),
                    event.getDelayLevel()
            );
            return;
        }
        rocketMQTemplate.syncSendOrderly(
                destination,
                message,
                event.getAggregateId(),
                properties.getSendTimeoutMs()
        );
    }
}
