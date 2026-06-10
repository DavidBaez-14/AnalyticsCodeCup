package terminus.co.edu.ufps.analytics.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;

@Configuration
public class RestClientConfig {

    @Value("${ms2.base-url}")
    private String ms2BaseUrl;

    @Value("${ms2.internal.secret}")
    private String ms2InternalSecret;

    @Value("${ms2.internal.timeout-seconds:5}")
    private int timeoutSeconds;

    @Bean
    public RestClient ms2InternalClient() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(timeoutSeconds));
        factory.setReadTimeout(Duration.ofSeconds(timeoutSeconds));
        return RestClient.builder()
                .baseUrl(ms2BaseUrl)
                .defaultHeader("X-Internal-Secret", ms2InternalSecret)
                .requestFactory(factory)
                .build();
    }
}
