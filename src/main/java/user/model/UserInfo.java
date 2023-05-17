package user.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.*;

import javax.persistence.*;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;


@AllArgsConstructor
@NoArgsConstructor
@Data
@Builder
@EqualsAndHashCode(callSuper=false)
@Entity(name = "USER_INFO")
public class UserInfo extends BaseEntity implements Serializable {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "USER_CD", unique = true, nullable = false)
    private Long userCd;

    @Column(name = "USER_NICK_NM",nullable = true)
    private String userNickNm;

    @Column(name = "ABOUT_ME", nullable = true)
    private String aboutMe;

    @OneToMany(mappedBy = "userInfo", fetch = FetchType.LAZY, cascade = CascadeType.ALL) @Builder.Default @JsonIgnore
    private List<UserProfileImage> userProfileImages = new ArrayList<>();

    public void addUserProfileImage(UserProfileImage userProfileImage) {
        this.userProfileImages.add(userProfileImage);
    }

    public void setUserProfileImages(List<UserProfileImage> userProfileImages) {
        this.userProfileImages = userProfileImages;
        userProfileImages.forEach(o -> o.setUserInfo(this));
    }

    //@Column(name = "PROFILE_IMG_URL", nullable = true)
    //private String profileImgUrl;
}