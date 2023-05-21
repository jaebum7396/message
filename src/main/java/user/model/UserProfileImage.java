package user.model;

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

    @Column(name = "USER_CD")
    private Long userCd;

    @Column(name = "PROFILE_IMG_URL", nullable = true)
    private String profileImgUrl;

    //@ManyToOne @JoinColumn(name = "USER_CD") @JsonBackReference
    //private UserInfo userInfo;
}