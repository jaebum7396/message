package user.service;

import lombok.RequiredArgsConstructor;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import user.model.CustomUserDetails;
import user.model.User;
import user.repository.AuthRepository;

@Service
@RequiredArgsConstructor
public class CustomUserDetailsService implements UserDetailsService {
    private final AuthRepository authRepository;
    @Override
    public UserDetails loadUserByUsername(String userId) throws UsernameNotFoundException {
        User member = authRepository.findByUserId(userId).orElseThrow(
            () -> new UsernameNotFoundException("Invalid authentication!")
        );
        return new CustomUserDetails(member);
    }
}