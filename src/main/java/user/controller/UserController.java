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
import user.service.UserService;

import javax.servlet.http.HttpServletRequest;

@Slf4j
@Api(tags = "UserController")
@Tag(name = "UserController", description = "회원가입, 유저정보")
@RestController
@RequiredArgsConstructor
@RequestMapping("/user")
public class UserController {
    @Autowired
    UserService userService;
    @PostMapping(value = "/signup")
    @Operation(summary="회원가입", description="회원 가입 API")
    public ResponseEntity signup(@RequestBody SignupRequest signupRequest) throws Exception {
        return userService.signup(signupRequest);
    }
    @GetMapping(value = "/me")
    @Operation(summary="내 정보 보기", description="가입한 회원 정보를 가져오는 API(로그인 후 인증정보 - jwt token 필수)")
    public ResponseEntity getMyInfo(HttpServletRequest request) {
        return userService.getMyInfo(request);
    }
}