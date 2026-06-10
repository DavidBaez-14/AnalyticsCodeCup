package terminus.co.edu.ufps.analytics;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

@EnableAsync
@SpringBootApplication
public class AnalyticsCodeCupApplication {

    public static void main(String[] args) {
        SpringApplication.run(AnalyticsCodeCupApplication.class, args);
    }
}
