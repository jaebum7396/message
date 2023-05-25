package user.model;

import lombok.*;
import org.hibernate.annotations.GenericGenerator;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.Id;
import java.io.Serializable;

@AllArgsConstructor
@NoArgsConstructor
@Data
@Builder
@EqualsAndHashCode(callSuper=false)
@Entity(name = "USER_PROFILE_IMAGE")
public class UserProfileImage extends BaseEntity implements Serializable {
    @Id
    @GeneratedValue(generator = "uuid")
    @GenericGenerator(name = "uuid", strategy = "org.hibernate.id.UUIDGenerator")
    @Column( name = "USER_PROFILE_IMAGE_CD")
    private String userProfileImageCd;

    @Column(name = "USER_CD")
    private String userCd;

    @Column(name = "PROFILE_IMG_URL", nullable = true)
    private String profileImgUrl;

    //@ManyToOne @JoinColumn(name = "USER_CD") @JsonBackReference
    //private UserInfo userInfo;
}