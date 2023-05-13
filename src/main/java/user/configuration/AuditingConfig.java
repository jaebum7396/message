package user.configuration;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.AuditorAware;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import user.model.User;

import java.util.Optional;

@Slf4j
@Configuration
public class AuditingConfig {
    @Bean
    public AuditorAware<Long> auditorProvider() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            log.debug("Not found AuthenticationToken");
            return null;
        }
        User userEntity = (User) authentication.getDetails();
        return () -> Optional.of(userEntity.getIdx());
    }
}
