package user.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import user.model.User;

import java.util.Optional;

public interface UserRepositoryQ {
    Page<User> findUsersWithPageable(String query, Pageable pageable);
    Optional<User> getMyInfo(String userId);
}
