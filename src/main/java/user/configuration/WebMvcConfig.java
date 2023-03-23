package user.configuration;

import org.springframework.context.annotation.Configuration; 
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebMvcConfig implements WebMvcConfigurer {
	@Override
	public void addInterceptors(InterceptorRegistry reg) {
		reg.addInterceptor(new MyInterceptor())
		.addPathPatterns("/*")
		.excludePathPatterns("/css/**", "/images/**", "/js/**");
	}
}
