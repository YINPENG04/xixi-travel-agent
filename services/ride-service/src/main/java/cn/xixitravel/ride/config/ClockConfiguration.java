package cn.xixitravel.ride.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration
public class ClockConfiguration {

    @Bean
    Clock utcClock() {
        return Clock.systemUTC();
    }
}
