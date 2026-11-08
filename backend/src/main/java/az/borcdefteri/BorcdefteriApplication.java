package az.borcdefteri;

import az.borcdefteri.common.AppProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties(AppProperties.class)
public class BorcdefteriApplication {

    public static void main(String[] args) {
        SpringApplication.run(BorcdefteriApplication.class, args);
    }
}
