package trade;

import com.binance.connector.client.WebSocketApiClient;
import com.binance.connector.client.impl.WebSocketApiClientImpl;
import org.json.JSONObject;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.data.mongodb.repository.config.EnableMongoRepositories;

import javax.annotation.PostConstruct;

@EnableDiscoveryClient
@EnableJpaAuditing
@SpringBootApplication(exclude = {SecurityAutoConfiguration.class})
@EnableMongoRepositories(basePackages = "trade.future.mongo")
@EnableJpaRepositories(basePackages = "trade.future.repository")
public class Application {
	private static final int waitTime = 3000;
	public static void main(String[] args) throws InterruptedException {
		SpringApplication.run(Application.class, args);
	}

	/*@PostConstruct
	public void attachShutdownHook() {
		Runtime.getRuntime().addShutdownHook(new Thread(() -> {
			// 애플리케이션 종료 시 수행할 작업
			System.out.println("JVM 종료 중입니다. 종료 작업을 수행합니다.");
			// 예: 리소스 정리, 로그 남기기 등등
		}));
	}*/
}
