package message;

import lombok.extern.slf4j.Slf4j;
import message.common.Restarter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import message.configuration.RedisSubscriber;
import org.springframework.scheduling.annotation.Scheduled;

//@EnableMongoRepositories(basePackages = "trade.future.mongo")
@EnableDiscoveryClient
@EnableJpaAuditing
@SpringBootApplication(exclude = {SecurityAutoConfiguration.class})
@EnableJpaRepositories(basePackages = "message.repository")
@Slf4j
public class Application implements CommandLineRunner {
	private static final int waitTime = 3000;
	@Autowired private Restarter restarter;  // Restarter 주입
	public static void main(String[] args) throws InterruptedException {
		SpringApplication.run(Application.class, args);
	}

	private final RedisSubscriber redisSubscriber;
	@Autowired
	public Application(RedisSubscriber redisSubscriber) {
		this.redisSubscriber = redisSubscriber;
	}
	@Override
	public void run(String... args) {
		// 구독 시작
		redisSubscriber.subscribe();
	}

	@Scheduled(cron = "0 0 * * * *")
	public void hourlyRestart() {
		log.info("Application restart initiated hourly");
		restarter.restartApplication();
	}
	/*@PostConstruct
	public void attachShutdownHook() {
		Runtime.getRuntime().addShutdownHook(new Thread(() -> {
			log.info("JVM 종료 중입니다. 종료 작업을 수행합니다.");
			// 애플리케이션 종료 시 수행할 작업
		}));
	}*/
}

