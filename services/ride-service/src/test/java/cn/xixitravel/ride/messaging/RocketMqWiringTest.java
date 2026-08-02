package cn.xixitravel.ride.messaging;

import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class RocketMqWiringTest {
    @Autowired
    private RocketMQTemplate rocketMQTemplate;

    @Test
    void createsRocketMqTemplateFromApplicationConfiguration() {
        assertThat(rocketMQTemplate).isNotNull();
    }
}
