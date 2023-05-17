package user.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import user.model.UserInfo;
import user.model.UserProfileImage;
@Repository
public interface UserProfileImageRepository extends JpaRepository<UserProfileImage, UserInfo> {

}
