package message.service;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.Key;

@Slf4j
@Service
@Transactional
public class MessageService {
    // ****************************************************************************************
    // 상수 세팅
    // ****************************************************************************************
    // JWT
    @Value("${jwt.secret.key}")
    private String JWT_SECRET_KEY;

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