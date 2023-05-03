package user.controller;

import io.swagger.annotations.Api;
import io.swagger.annotations.ApiResponse;
import io.swagger.annotations.ApiResponses;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import user.model.LoginRequest;
import user.model.Response;
import user.service.AuthService;

@Api(tags = "AuthenticationController")
@Tag(name = "AuthenticationController", description = "회원가입, 로그인, 유저정보")
@RestController
public class AuthController {
    @Autowired
    AuthService authService;
    @PostMapping(value = "/login")
    @Operation(summary="로그인", description="가입한 회원을 로그인 하는 API")
    @ApiResponses({
        @ApiResponse(code = 200, message="ok",response = Response.class),
        @ApiResponse(code = 400, message="잘못된 요청",response = Response.class),
        @ApiResponse(code = 500, message="서버 에러",response = Response.class)
    })
    public ResponseEntity login(@RequestBody LoginRequest loginRequest) throws Exception {
        return authService.login(loginRequest);
    }
}