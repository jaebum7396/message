package user.service;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
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
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired private final AES128Util aes128Util = new AES128Util();

    @Value("${jwt.secret.key}")
    String JWT_SECRET_KEY;

    public ResponseEntity signup(SignupRequest signupRequest) throws Exception {
        Response responseResult;
        Map<String, Object> resultMap = new LinkedHashMap<String, Object>();
        try {
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

            responseResult = Response.builder()
                    .statusCode(HttpStatus.OK.value())
                    .status(HttpStatus.OK)
                    .message("가입 성공")
                    .result(resultMap).build();
            return ResponseEntity.ok().body(responseResult);
        }catch(BadCredentialsException be){
            System.out.println(be.getMessage());
            responseResult = Response.builder()
                    .statusCode(HttpStatus.BAD_REQUEST.value())
                    .status(HttpStatus.BAD_REQUEST)
                    .message(be.getMessage())
                    .result(resultMap).build();
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(responseResult);
        } catch (Exception e) {
            e.printStackTrace();
            responseResult = Response.builder()
                    .statusCode(HttpStatus.INTERNAL_SERVER_ERROR.value())
                    .status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .message("서버쪽 오류가 발생했습니다. 관리자에게 문의하십시오")
                    .result(resultMap).build();
            return ResponseEntity.internalServerError().body(responseResult);
        }
    }
    public ResponseEntity getMyInfo(HttpServletRequest request){
        Response responseResult;
        Map<String, Object> resultMap = new LinkedHashMap<String, Object>();
        Key secretKey = Keys.hmacShaKeyFor(JWT_SECRET_KEY.getBytes(StandardCharsets.UTF_8));
        System.out.println("secretKey : " + secretKey);
        try{
            String userNm = Jwts.parserBuilder().setSigningKey(secretKey).build()
                    .parseClaimsJws(request.getHeader("authorization")).getBody()
                    .getSubject();
            //final Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
            if (userNm == null || userNm == null) {
                throw new BadCredentialsException("토큰 인증에 실패하였습니다.");
            }
            System.out.println("userNm : " + userNm);
            User userEntity = userRepository.findByUserId(userNm).get();
            resultMap.put("userId", userEntity.getUserId());
            resultMap.put("password", userEntity.getUserPw());
            resultMap.put("name", userEntity.getUserNm());
            //resultMap.put("regNo", aes128Util.decrypt(userEntity.getRegNo()));

            responseResult = Response.builder()
                    .statusCode(HttpStatus.OK.value())
                    .status(HttpStatus.OK)
                    .message("유저 정보 요청 성공")
                    .result(resultMap).build();

            return ResponseEntity.ok().body(responseResult);
        }catch(BadCredentialsException be){
            responseResult = Response.builder()
                    .statusCode(HttpStatus.BAD_REQUEST.value())
                    .status(HttpStatus.BAD_REQUEST)
                    .message(be.getMessage())
                    .result(resultMap).build();

            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(responseResult);
        }catch(Exception e){
            e.printStackTrace();
            responseResult = Response.builder()
                    .statusCode(HttpStatus.INTERNAL_SERVER_ERROR.value())
                    .status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .message("서버쪽 오류가 발생했습니다. 관리자에게 문의하십시오")
                    .result(resultMap).build();

            return ResponseEntity.internalServerError().body(responseResult);
        }
    }
    //중복된 아이디인지 검증
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
}