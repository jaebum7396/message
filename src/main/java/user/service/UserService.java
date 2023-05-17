package user.service;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import user.model.*;
import user.repository.UserInfoRepository;
import user.repository.UserProfileImageRepository;
import user.repository.UserRepository;
import user.utils.AES128Util;

import javax.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.Key;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
@Transactional
@RequiredArgsConstructor
public class UserService implements UserDetailsService {
    @Autowired UserRepository userRepository;
    @Autowired UserInfoRepository userInfoRepository;
    @Autowired UserProfileImageRepository userProfileImageRepository;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired private final AES128Util aes128Util = new AES128Util();
    private final RedisTemplate<String, Object> redisTemplate;
    @Value("${jwt.secret.key}")
    private String JWT_SECRET_KEY;

    public Claims getClaims(HttpServletRequest request) {
        Key secretKey = Keys.hmacShaKeyFor(JWT_SECRET_KEY.getBytes(StandardCharsets.UTF_8));
        Claims claim = Jwts.parserBuilder().setSigningKey(secretKey).build()
                .parseClaimsJws(request.getHeader("authorization")).getBody();
        return claim;
    }
    public boolean duplicateIdValidate(SignupRequest signupRequest) {
        boolean check = userRepository.findByUserId(signupRequest.getUserId()).isPresent();
        return check;
    }
    @Override
    public UserDetails loadUserByUsername(String name) throws UsernameNotFoundException {
        User userEntity = userRepository.findByUserNm(name).orElseThrow(
                () -> new UsernameNotFoundException("Invalid authentication!")
        );
        return new CustomUserDetails(userEntity);
    }

    public ResponseEntity signup(SignupRequest signupRequest) throws Exception {
        Response response;
        Map<String, Object> resultMap = new LinkedHashMap<String, Object>();
        //중복 아이디 검사
        if (duplicateIdValidate(signupRequest)) {
            throw new BadCredentialsException("중복된 아이디입니다.");
        }
        signupRequest.setUserPw(passwordEncoder.encode(signupRequest.getUserPw()));
        User userEntity = signupRequest.toEntity();
        //ROLE 설정
        userEntity.setRoles(Collections.singletonList(Auth.builder().authType("ROLE_USER").build()));

        userRepository.save(userEntity);

        resultMap.put("userId", userEntity.getUserId());
        resultMap.put("userNm", userEntity.getUserNm());
        resultMap.put("roles", userEntity.getRoles());

        response = Response.builder()
                .statusCode(HttpStatus.OK.value())
                .status(HttpStatus.OK)
                .message("가입 성공")
                .result(resultMap).build();
        return ResponseEntity.ok().body(response);
    }

    public ResponseEntity saveUserInfo(HttpServletRequest request, UserInfo updateUserInfo){
        System.out.println("UserService.saveUserInfo.params : " + updateUserInfo.toString());
        Response response;
        Map<String, Object> resultMap = new LinkedHashMap<String, Object>();

        Claims claim = getClaims(request);
        Long userCd = claim.get("userCd", Long.class);
        UserInfo userInfo = userInfoRepository.findByUserCd(userCd).orElseGet(() -> {
            return UserInfo.builder()
                .userCd(userCd)
                .build();
        });
        // 업데이트할 필드가 있다면 업데이트합니다.
        if (updateUserInfo.getUserNickNm() != null) {
            userInfo.setUserNickNm(updateUserInfo.getUserNickNm());
        }
        if (updateUserInfo.getAboutMe() != null) {
            userInfo.setAboutMe(updateUserInfo.getAboutMe());
        }
        if (updateUserInfo.getUserProfileImages().size() != 0) {
            for(UserProfileImage upi : updateUserInfo.getUserProfileImages()){
                upi.setUserInfo(userInfo);
                System.out.println(upi);
                //userProfileImageRepository.save(upi);
                userInfo.addUserProfileImage(upi);
            }
        }
        userInfo = userInfoRepository.save(userInfo);
        System.out.println("redis 전송 userInfo: " + userInfo.toString());
        redisTemplate.convertAndSend("updateUserInfo", userInfo);
        resultMap.put("userInfo", userInfo);

        response = Response.builder()
                .statusCode(HttpStatus.OK.value())
                .status(HttpStatus.OK)
                .message("유저 정보 업데이트 성공")
                .result(resultMap).build();

        return ResponseEntity.ok().body(response);
    }

    public ResponseEntity getMyInfo(HttpServletRequest request){
        Response response;
        Map<String, Object> resultMap = new LinkedHashMap<String, Object>();

        Claims claim = getClaims(request);
        String userId = claim.getSubject();
        User userEntity = userRepository.findByUserId(userId).get();
        resultMap.put("user", userEntity);

        response = Response.builder()
            .statusCode(HttpStatus.OK.value())
            .status(HttpStatus.OK)
            .message("유저 정보 요청 성공")
            .result(resultMap).build();

        return ResponseEntity.ok().body(response);
    }
    //중복된 아이디인지 검증
}