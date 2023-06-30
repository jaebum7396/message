package user.service;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import user.jwt.JwtProvider;
import user.model.CustomUserDetails;
import user.model.LoginRequest;
import user.model.Response;
import user.model.User;
import user.repository.AuthRepository;
import user.utils.AES128Util;

import java.util.LinkedHashMap;
import java.util.Map;

@Service
@Transactional
@RequiredArgsConstructor
public class AuthService implements UserDetailsService {
    @Autowired
    AuthRepository authRepository;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    JwtProvider jwtProvider;
    private final AES128Util aes128Util = new AES128Util();
    public Map<String, Object> login(LoginRequest loginRequest) throws Exception {
        Map<String, Object> resultMap = new LinkedHashMap<String, Object>();
        User userEntity = authRepository.findByUserId(loginRequest.getUserId()).orElseThrow(() ->
            new BadCredentialsException(loginRequest.getUserId()+": 아이디가 존재하지 않습니다."));
        if (!passwordEncoder.matches(loginRequest.getUserPw(), userEntity.getUserPw())) {
            throw new BadCredentialsException("잘못된 비밀번호입니다.");
        }
        resultMap.put("userId", userEntity.getUserId());
        resultMap.put("name", userEntity.getUserNm());
        resultMap.put("roles", userEntity.getRoles());
        resultMap.put("token", jwtProvider.createToken(
                userEntity.getDomainCd()
                , userEntity.getUserCd()
                , userEntity.getUserId()
                , userEntity.getRoles()));

        return resultMap;
    }
    public boolean loginPasswordValidate(LoginRequest loginRequest, User userEntity) {
        boolean check = passwordEncoder.matches(loginRequest.getUserPw(), userEntity.getUserPw());
        return check;
    }
    @Override
    public UserDetails loadUserByUsername(String userNm) throws UsernameNotFoundException {
        User userEntity = authRepository.findByUserNm(userNm).orElseThrow(
            () -> new UsernameNotFoundException("Invalid authentication!")
        );
        return new CustomUserDetails(userEntity);
    }
}