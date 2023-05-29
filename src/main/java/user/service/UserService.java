package user.service;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.BadCredentialsException;
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
import java.util.*;

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

    public Claims getClaims(HttpServletRequest request){
        try{
            Key secretKey = Keys.hmacShaKeyFor(JWT_SECRET_KEY.getBytes(StandardCharsets.UTF_8));
            Claims claim = Jwts.parserBuilder().setSigningKey(secretKey).build()
                    .parseClaimsJws(request.getHeader("authorization")).getBody();
            return claim;
        } catch (ExpiredJwtException e) {
            throw new ExpiredJwtException(null, null, "로그인 시간이 만료되었습니다.");
        }
    }

    public ResponseEntity signup(SignupRequest signupRequest) throws Exception {
        System.out.println("UserService.signup.params : " + signupRequest.toString());
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

        UserInfo userInfo = UserInfo.builder()
                .userCd(userEntity.getUserCd())
                .userNickNm(userEntity.getUserNm())
                .build();
        userInfo.setDeleteYn('N');

        String userProfileImageCommon = "image/profile/common.png";
        System.out.println("userGender : "+userEntity.getUserGender());
        if(userEntity.getUserGender().equals("M")){
            userProfileImageCommon = "image/profile/man_common.png";
        }else if(userEntity.getUserGender().equals("W")){
            userProfileImageCommon = "image/profile/woman_common.png";
        }

        userInfo.addUserProfileImage(UserProfileImage.builder()
                .userCd(userEntity.getUserCd())
                .profileImgUrl(userProfileImageCommon)
                .build());
        userEntity.setUserInfo(userInfo);

        userRepository.save(userEntity);

        //레디스를 통해 friendInfo 또한 저장 해준다
        System.out.println("redis 전송 userInfo: " + userInfo.toString());
        redisTemplate.convertAndSend("updateUserInfo", userInfo);
        resultMap.put("userInfo", userInfo);

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
        String userCd = claim.get("userCd", String.class);
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
                //upi.setUserInfo(userInfo);
                //userProfileImageRepository.save(upi);
                System.out.println(upi);
                upi.setUserCd(userCd);
                userInfo.addUserProfileImage(upi);
            }
        }
        userInfo = userInfoRepository.save(userInfo);

        Pageable page = Pageable.ofSize(10);
        //Page<User> usersPage = userRepository.findUsersWithPageable(userInfo.getUserCd(), page);
        //System.out.println("usersPage = " + usersPage.getContent().toString());

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

    public ResponseEntity getUsersWithPageable(HttpServletRequest request, String queryString, Pageable page) {
        Response response;
        Map<String, Object> resultMap = new LinkedHashMap<String, Object>();
        HashMap<String, Object> paramMap = new HashMap<String, Object>();
        List<User> userArr = new ArrayList<User>();

        Claims claim = getClaims(request);
        String userCd = claim.get("userCd", String.class);

        Page<User> usersPage = userRepository.findUsersWithPageable(queryString, page);
        userArr = usersPage.getContent();

        resultMap.put("userArr", userArr);
        resultMap.put("p_page", page.getPageNumber());

        response = Response.builder()
                .statusCode(HttpStatus.OK.value())
                .status(HttpStatus.OK)
                .message("요청 성공")
                .result(resultMap).build();
        return ResponseEntity.ok().body(response);
    }
}