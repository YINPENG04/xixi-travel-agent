package cn.xixitravel.ride;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class XixiRideApplication {

    public static void main(String[] args) {
        SpringApplication.run(XixiRideApplication.class, args);
    }
}
