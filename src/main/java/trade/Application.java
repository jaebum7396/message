package trade;

import com.binance.connector.client.WebSocketApiClient;
import com.binance.connector.client.impl.WebSocketApiClientImpl;
import org.json.JSONObject;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

@EnableDiscoveryClient
@EnableJpaAuditing
@SpringBootApplication(exclude = {SecurityAutoConfiguration.class})
public class Application {
	private static final int waitTime = 3000;
	public static void main(String[] args) throws InterruptedException {
		SpringApplication.run(Application.class, args);
	}
}
