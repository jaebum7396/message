package user.controller;

import io.swagger.annotations.Api;
import io.swagger.annotations.ApiResponse;
import io.swagger.annotations.ApiResponses;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import user.model.Response;
import user.model.SignupRequest;
import user.service.UserService;

import javax.servlet.http.HttpServletRequest;
import java.util.Enumeration;

@Api(tags = "UserController")
@Tag(name = "UserController", description = "회원가입, 유저정보")
@Slf4j
@RestController
public class UserController {
    @Autowired
    UserService userService;

    @PostMapping(value = "/signup")
    @Operation(summary="회원가입", description="회원 가입 API")
    @ApiResponses({
        @ApiResponse(code = 200, message="ok",response = Response.class),
        @ApiResponse(code = 400, message="잘못된 요청",response = Response.class),
        @ApiResponse(code = 500, message="서버 에러",response = Response.class)
    })
    public ResponseEntity signup(@RequestBody SignupRequest signupRequest) throws Exception {
        return userService.signup(signupRequest);
    }

    @GetMapping(value = "/me")
    @Operation(summary="내 정보 보기", description="가입한 회원 정보를 가져오는 API(로그인 후 인증정보 - jwt token 필수)")
    @ApiResponses({
        @ApiResponse(code = 200, message="ok",response = Response.class),
        @ApiResponse(code = 400, message="잘못된 요청",response = Response.class),
        @ApiResponse(code = 500, message="서버 에러",response = Response.class)
    })
    public ResponseEntity getMyInfo(HttpServletRequest request) {
        log.info("getMyInfo");
        Enumeration eHeader = request.getHeaderNames();
        while (eHeader.hasMoreElements()) {
            String key = (String)eHeader.nextElement();
            String value = request.getHeader(key);
            log.info("key : " + key + " ===> value : " + value);
        }
        return userService.getMyInfo();
    }
}