package user.configuration;

import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.ModelAndView;
import user.model.Response;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.Map;

@Slf4j
public class MyInterceptor implements HandlerInterceptor{
	private Logger logger = LoggerFactory.getLogger(MyInterceptor.class);
	private String GATEWAY_URI;
	private String ACTIVE_PROFILE;

	public MyInterceptor(String GATEWAY_URI, String ACTIVE_PROFILE) {
		this.GATEWAY_URI = GATEWAY_URI;
		this.ACTIVE_PROFILE = ACTIVE_PROFILE;
	}

	@Override
	public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
		log.info("===============================================");
        log.info("==================== BEGIN ====================");
        Enumeration eHeader = request.getHeaderNames();
    	while (eHeader.hasMoreElements()) {
	    	String key = (String)eHeader.nextElement();
	    	String value = request.getHeader(key);
	    	log.info("key : " + key + " ===> value : " + value);
	    }

		String requestUri = request.getHeader("x-forwarded-host");

		System.out.println("ACTIVE_PROFILE : " + ACTIVE_PROFILE);
		System.out.println("GATEWAY_URI : " + GATEWAY_URI);
		if(ACTIVE_PROFILE.equals("local")) {
			System.out.println("로컬에서 실행중입니다.");
			//GATEWAY_URI = "http://localhost:8080";
		}else{
			System.out.println("요청_URI : " + requestUri);
			if(("".equals(requestUri)||requestUri == null||!requestUri.equals(GATEWAY_URI))) {
				throw new BadCredentialsException("잘못된 접근입니다.");
			}
		}
		return HandlerInterceptor.super.preHandle(request, response, handler);
	}

	@Override
	public void postHandle(HttpServletRequest request, HttpServletResponse response, Object handler, ModelAndView modelAndView) throws Exception {
		log.info("==================== END ======================");
        log.info("===============================================");
		HandlerInterceptor.super.postHandle(request, response, handler, modelAndView);
	}

	@Override
	public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex)	throws Exception {
		// TODO Auto-generated method stub
		HandlerInterceptor.super.afterCompletion(request, response, handler, ex);
	}
	
}
