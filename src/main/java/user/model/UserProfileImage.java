package user.model;

import lombok.*;

import javax.persistence.*;


@AllArgsConstructor
@NoArgsConstructor
@Data
@Builder
@EqualsAndHashCode(callSuper=false)
@Entity(name = "USER_PROFILE_IMAGE")
public class UserProfileImage extends BaseEntity {
    @Id @ManyToOne @JoinColumn(name = "USER_CD")
    private UserInfo userInfo;

    @Column(name = "PROFILE_IMG_URL", nullable = true)
    private String profileImgUrl;
}