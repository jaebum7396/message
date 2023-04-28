package user.configuration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebMvcConfig implements WebMvcConfigurer {
	@Value("${cloud.gateway.uri}")
	String GATEWAY_URI;
	@Override
	public void addInterceptors(InterceptorRegistry reg) {
		reg.addInterceptor(new MyInterceptor(GATEWAY_URI))
		.addPathPatterns("/*")
		.excludePathPatterns("/css/**", "/images/**", "/js/**");
	}
}
