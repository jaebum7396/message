package user.model;

import lombok.*;
import lombok.experimental.SuperBuilder;

import javax.persistence.*;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;


@AllArgsConstructor
@NoArgsConstructor
@Data
@SuperBuilder
@Builder
@EqualsAndHashCode(callSuper=false)
@Entity(name = "USER_INFO")
public class UserInfo extends BaseEntity implements Serializable {
    @Id
    @Column(name = "USER_CD")
    private String userCd;

    @Column(name = "USER_NICK_NM",nullable = true)
    private String userNickNm;

    @Column(name = "ABOUT_ME", nullable = true)
    private String aboutMe;

    @OneToMany(fetch = FetchType.LAZY, cascade = CascadeType.ALL) @Builder.Default
    @JoinColumn(name = "USER_CD")
    private List<UserProfileImage> userProfileImages = new ArrayList<>();

    public void addUserProfileImage(UserProfileImage userProfileImage) {
        this.userProfileImages.add(userProfileImage);
        //if (userProfileImage.getUserInfo() != this) {
        //    userProfileImage.setUserInfo(this); // UserProfileImage와의 양방향 관계 설정
        //}
    }

    public void setUserProfileImages(List<UserProfileImage> userProfileImages) {
        this.userProfileImages = userProfileImages;
    }
}