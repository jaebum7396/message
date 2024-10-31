package message.service;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import message.model.entity.MessageEntity;
import message.repository.MessageRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.Key;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@Transactional
public class MessageService {
    @Autowired
    MessageRepository messageRepository;
    // ****************************************************************************************
    // 상수 세팅
    // ****************************************************************************************
    // JWT
    @Value("${jwt.secret.key}")
    private String JWT_SECRET_KEY;

    public void saveMessage(MessageEntity messageEntity) {
        // 메시지 저장
        messageRepository.save(messageEntity);
    }

    public Map<String, Object> getPrevMessages(HttpServletRequest request, String topic, Pageable page) {
        Map<String, Object> resultMap = new LinkedHashMap<String, Object>();

        Page<MessageEntity> messagesPage = messageRepository.findMessagesWithPageable(topic, page);
        List<MessageEntity> messageArr = messagesPage.getContent();

        resultMap.put("messageArr", messageArr);
        resultMap.put("p_page", page.getPageNumber());

        return resultMap;
    }

    // ****************************************************************************************
    // JSON 데이터 파싱 관련 메서드
    // ****************************************************************************************
    public Claims getClaims(HttpServletRequest request){
        try{
            Key secretKey = Keys.hmacShaKeyFor(JWT_SECRET_KEY.getBytes(StandardCharsets.UTF_8));
            log.info("JWT_SECRET_KEY : " + JWT_SECRET_KEY);
            log.info("authorization : " + request.getHeader("authorization"));
            Claims claim = Jwts.parserBuilder().setSigningKey(secretKey).build()
                    .parseClaimsJws(request.getHeader("authorization")).getBody();
            return claim;
        } catch (ExpiredJwtException e) {
            throw new ExpiredJwtException(null, null, "로그인 시간이 만료되었습니다.");
        } catch (Exception e) {
            throw new BadCredentialsException("인증 정보에 문제가 있습니다.");
        }
    }
}