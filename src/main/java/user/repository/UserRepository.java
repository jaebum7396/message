package user.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import user.model.User;

import java.util.List;
import java.util.Optional;
@Repository
public interface UserRepository extends JpaRepository<User, String> {
    User save(User userEntity);
    Optional<User> findByUserId(String userId);
    Optional<User> findByUserNm(String userNm);
    List<User> findAll();
}