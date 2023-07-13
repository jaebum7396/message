package user.service;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import user.model.*;
import user.repository.AuthRepository;
import user.repository.RefreshTokenRepository;
import user.utils.AES128Util;

import javax.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;
import java.security.Key;
import java.util.*;

@Service
@Transactional
@RequiredArgsConstructor
public class AuthService implements UserDetailsService {
    @Autowired AuthRepository authRepository;
    @Autowired RefreshTokenRepository refreshTokenRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Value("${jwt.secret.key}") private String salt;
    private Key secretKey;
    // 만료시간
    @Value("${token.access-expired-time}") private final long ACCESS_EXPIRED_TIME;
    // 재발급 토큰 만료시간
    @Value("${token.refresh-expired-time}") private final long REFRESH_EXPIRED_TIME;
    private final AES128Util aes128Util = new AES128Util();
    @PostConstruct
    protected void init() {
        secretKey = Keys.hmacShaKeyFor(salt.getBytes(StandardCharsets.UTF_8));
    }

    public String createAccessToken(String domainCd, String userCd, String userId, Set<Auth> roles) {
        Claims claims = Jwts.claims().setSubject(userId);
        claims.put("domainCd", domainCd);
        claims.put("userCd", userCd);
        //claims.put("roles", roles);
        return Jwts.builder()
                .setClaims(claims)
                .setExpiration(new Date(System.currentTimeMillis()  + ACCESS_EXPIRED_TIME))
                .setIssuedAt(new Date())
                .signWith(secretKey, SignatureAlgorithm.HS256)
                .compact();
    }

    public String createRefreshToken() {
        Claims claims = Jwts.claims();
        claims.put("value", UUID.randomUUID());
        return Jwts.builder()
                .addClaims(claims)
                .setExpiration(new Date(System.currentTimeMillis() + REFRESH_EXPIRED_TIME))
                .setIssuedAt(new Date())
                .signWith(secretKey, SignatureAlgorithm.HS256)
                .compact();
    }

    public Map<String, Object> generateRefreshToken(LoginRequest loginRequest) {
        Map<String, Object> resultMap = new LinkedHashMap<String, Object>();
        User userEntity = authRepository.findByUserId(loginRequest.getUserId()).orElseThrow(() ->
                new BadCredentialsException(loginRequest.getUserId()+" : 아이디가 존재하지 않습니다."));
        if (!passwordEncoder.matches(loginRequest.getUserPw(), userEntity.getUserPw())) {
            throw new BadCredentialsException("잘못된 비밀번호입니다.");
        }
        String userCd = userEntity.getUserCd();

        RefreshToken refreshToken = new RefreshToken(UUID.randomUUID().toString(), userCd);
        refreshTokenRepository.save(refreshToken);
        resultMap.put("token", refreshToken);

        return resultMap;
    }

    public Map<String, Object> login(LoginRequest loginRequest) throws Exception {
        Map<String, Object> resultMap = new LinkedHashMap<String, Object>();
        User userEntity = authRepository.findByUserId(loginRequest.getUserId()).orElseThrow(() ->
            new BadCredentialsException(loginRequest.getUserId()+" : 아이디가 존재하지 않습니다."));
        if (!passwordEncoder.matches(loginRequest.getUserPw(), userEntity.getUserPw())) {
            throw new BadCredentialsException("잘못된 비밀번호입니다.");
        }
        resultMap.put("userId", userEntity.getUserId());
        resultMap.put("name", userEntity.getUserNm());
        resultMap.put("roles", userEntity.getRoles());
        resultMap.put("token", createAccessToken(
            userEntity.getDomainCd()
            , userEntity.getUserCd()
            , userEntity.getUserId()
            , userEntity.getRoles())
        );

        return resultMap;
    }
    @Override
    public UserDetails loadUserByUsername(String userNm) throws UsernameNotFoundException {
        User userEntity = authRepository.findByUserNm(userNm).orElseThrow(
            () -> new UsernameNotFoundException("Invalid authentication!")
        );
        return new CustomUserDetails(userEntity);
    }
}