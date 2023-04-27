package user.configuration;

import java.util.Enumeration;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.ModelAndView;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
public class MyInterceptor implements HandlerInterceptor{
	
	private Logger logger = LoggerFactory.getLogger(MyInterceptor.class);
	
	@Value("${gateway.uri}")
	private String GATEWAY_URI;
	
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
    	if(("".equals(requestUri)||requestUri == null||!requestUri.equals(GATEWAY_URI))) {
			log.info("GATEWAY_URI : "+ GATEWAY_URI);
			log.info("requestUri : "+ requestUri);
    		throw new BadCredentialsException("잘못된 접근입니다.");
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
