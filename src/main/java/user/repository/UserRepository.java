package user.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import user.model.UserEntity;

import java.util.List;
import java.util.Optional;
@Repository
public interface UserRepository extends JpaRepository<UserEntity, String> {
    UserEntity save(UserEntity userEntity);
    Optional<UserEntity> findByUserId(String userId);
    Optional<UserEntity> findByUserNm(String userNm);
    List<UserEntity> findAll();
}