package message.configuration;

import io.jsonwebtoken.ExpiredJwtException;
import lombok.extern.slf4j.Slf4j;
import message.common.model.Response;
import message.repository.MessageRepository;
import message.service.MessageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

@RestControllerAdvice
@Slf4j
public class ErrorResponseAdvice {
	private Logger logger = LoggerFactory.getLogger(ErrorResponseAdvice.class);
	@Autowired
	MessageService messageService;
	@Autowired
	MessageRepository messageRepository;

	@ExceptionHandler(Exception.class)
	public ResponseEntity handleException(Exception e) {
		Response responseResult;
        Map<String, Object> resultMap = new LinkedHashMap<String, Object>();
		e.printStackTrace();
        responseResult = Response.builder()
                .message("서버쪽 오류가 발생했습니다. 관리자에게 문의하십시오")
                .result(resultMap).build();
        return ResponseEntity.internalServerError().body(responseResult);
	}
	@ExceptionHandler(BadCredentialsException.class)
	public ResponseEntity handleBadCredentialsException(BadCredentialsException e) {
		Response responseResult;
        Map<String, Object> resultMap = new LinkedHashMap<String, Object>();
		e.printStackTrace();
        responseResult = Response.builder()
                .message("잘못된 접근입니다.")
                .result(resultMap).build();
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(responseResult);
	}
	@ExceptionHandler(ExpiredJwtException.class)
	public ResponseEntity handleExpiredJwtException(ExpiredJwtException e) {
		log.info("ExpiredJwtException");
		Response responseResult;
		Map<String, Object> resultMap = new LinkedHashMap<String, Object>();
		e.printStackTrace();
		responseResult = Response.builder()
				.message("로그인 시간이 만료되었습니다.")
				.result(resultMap).build();
		return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(responseResult);
	}
}
