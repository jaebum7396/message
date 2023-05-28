package user.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import user.model.User;

public interface UserRepositoryQ {
    Page<User> findUsersWithPageable(String query, Pageable pageable);
}
