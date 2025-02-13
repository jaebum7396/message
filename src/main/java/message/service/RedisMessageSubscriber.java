package message.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import message.model.dto.Envelope;
import message.model.entity.MessageEntity;
import message.repository.MessageRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

@Slf4j
@RequiredArgsConstructor
@Component
public class RedisMessageSubscriber implements MessageListener {
    private final ObjectMapper objectMapper;
    private final RedisTemplate redisTemplate;
    private final MessageService messageService;

    @Override
    public void onMessage(Message message, byte[] pattern) {
        log.info("메시지 수신");
        final DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
        String dateTimeKr = LocalDateTime.now(ZoneId.of("Asia/Seoul")).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        try {
            String messageString = (String) redisTemplate.getStringSerializer().deserialize(message.getBody());
            if (messageString == null) {
                log.error("메시지가 null임");
                return;
            }
            if (messageString.startsWith("\"") && messageString.endsWith("\"")) {
                messageString = messageString.substring(1, messageString.length() - 1);
                //log.info("2. 큰따옴표 제거 후: {}", messageString);
            }
            try {
                Envelope envelope = objectMapper.readValue(messageString, Envelope.class);
                String topic = envelope.getTopic();
                ObjectNode payload = envelope.getPayload();

                MessageEntity messageEntity = new MessageEntity();
                messageEntity.setTopic(topic);
                messageEntity.setUserCd(payload.get("connectionId").asText());
                messageEntity.setUserNm(payload.get("userNm").asText());
                messageEntity.setMessage(payload.get("message").asText());
                messageEntity.setMessageDt(dateTimeKr);
                messageService.saveMessage(messageEntity);
            } catch (Exception e) {
                e.printStackTrace();
                log.error("실패한 문자열: {}", messageString);
            }
        } catch (Exception e) {
            log.error(e.getMessage());
        }
    }
}