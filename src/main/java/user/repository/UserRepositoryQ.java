package user.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import user.model.User;

import java.util.UUID;

public interface UserRepositoryQ {
    Page<User> findUsersWithPageable(UUID userCd, Pageable pageable);
}
