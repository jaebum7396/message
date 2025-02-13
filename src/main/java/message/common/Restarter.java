package message.common;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class Restarter {

    private final ConfigurableApplicationContext applicationContext;

    public Restarter(ConfigurableApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
    }

    /**
     * 애플리케이션을 안전하게 재시작합니다.
     * @return 재시작 시도 성공 여부
     */
    public boolean restartApplication() {
        Thread restartThread = new Thread(() -> {
            try {
                System.out.println("Shutting down application...");
                // 현재 애플리케이션 종료
                applicationContext.close();

                System.out.println("Starting system restart...");
                // 시스템 레벨에서 재시작
                System.exit(0);  // JVM을 종료하면 Docker 등의 컨테이너 환경에서 자동으로 재시작됨

            } catch (Exception e) {
                System.err.println("Restart failed: " + e.getMessage());
                e.printStackTrace();
                System.exit(1);  // 에러 발생 시 비정상 종료 코드로 종료
            }
        });

        restartThread.setDaemon(false);
        restartThread.start();
        return true;
    }

    /**
     * 애플리케이션을 지정된 딜레이 후 재시작합니다.
     * @param delayMillis 재시작 전 대기할 시간(밀리초)
     * @return 재시작 시도 성공 여부
     */
    public boolean restartApplicationWithDelay(long delayMillis) {
        try {
            Thread.sleep(delayMillis);
            return restartApplication();
        } catch (InterruptedException e) {
            System.err.println("Restart delayed was interrupted: " + e.getMessage());
            return false;
        }
    }
}