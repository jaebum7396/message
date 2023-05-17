package user.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.*;

import javax.persistence.*;
import java.io.Serializable;


@AllArgsConstructor
@NoArgsConstructor
@Data
@Builder
@EqualsAndHashCode(callSuper=false)
@Entity(name = "USER_PROFILE_IMAGE")
public class UserProfileImage extends BaseEntity implements Serializable {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "USER_PROFILE_IMAGE_CD", unique = true, nullable = false)
    private Long userProfileImageCd;

    @Column(name = "PROFILE_IMG_URL", nullable = true)
    private String profileImgUrl;

    @ManyToOne @JoinColumn(name = "USER_CD") @JsonIgnore
    private UserInfo userInfo;

    public void setUserInfo(UserInfo userInfo) {
        this.userInfo = userInfo;
        if (!userInfo.getUserProfileImages().contains(this)) {
            userInfo.getUserProfileImages().add(this); // UserInfo와의 양방향 관계 설정
        }
    }
}