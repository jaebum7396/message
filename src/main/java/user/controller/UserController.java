package user.controller;

import io.swagger.annotations.Api;
import io.swagger.annotations.ApiResponse;
import io.swagger.annotations.ApiResponses;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import user.model.LoginRequest;
import user.model.Response;
import user.model.SignupRequest;
import user.model.UserInfo;
import user.service.AuthService;
import user.service.UserService;

import javax.servlet.http.HttpServletRequest;

@Slf4j
@Api(tags = "UserController")
@Tag(name = "UserController", description = "회원가입, 유저정보")
@RestController
@RequiredArgsConstructor
public class UserController {
    @Autowired UserService userService;
    @Autowired AuthService authService;

    @PostMapping(value = "/userInfo")
    @Operation(summary="회원 정보 갱신 API", description="회원 정보 갱신 API")
    public ResponseEntity UserInfo(HttpServletRequest request, @RequestBody UserInfo updateUserInfo) throws Exception {
        return userService.saveUserInfo(request, updateUserInfo);
    }
    @PostMapping(value = "/signup")
    @Operation(summary="회원가입", description="회원 가입 API")
    public ResponseEntity signup(@RequestBody SignupRequest signupRequest) throws Exception {
        return userService.signup(signupRequest);
    }
    @PostMapping(value = "/login")
    @Operation(summary="로그인", description="가입한 회원을 로그인 하는 API")
    public ResponseEntity login(@RequestBody LoginRequest loginRequest) throws Exception {
        return authService.login(loginRequest);
    }
    @GetMapping(value = "/me")
    @Operation(summary="내 정보 보기", description="가입한 회원 정보를 가져오는 API(jwt 인증 요구)")
    public ResponseEntity getMyInfo(HttpServletRequest request) {
        return userService.getMyInfo(request);
    }
}