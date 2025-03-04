package message.common;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.stereotype.Component;
import message.service.MessageService;

@Component
@Slf4j
public class ShutdownHandler implements ApplicationListener<ContextClosedEvent> {

    @Autowired
    MessageService messageService;

    @Override
    public void onApplicationEvent(ContextClosedEvent event) {
        // 애플리케이션 종료 시 수행할 작업
        log.info("애플리케이션이 종료됩니다. 종료 작업을 수행합니다.");
    }
}
