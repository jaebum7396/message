package user.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.DynamicInsert;

import javax.persistence.*;
import java.util.ArrayList;
import java.util.List;
@DynamicInsert
@AllArgsConstructor
@NoArgsConstructor
@Getter
@Builder
@Entity(name = "TB_USER")
public class UserEntity extends BaseEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "userCd")
    private Long userCd;

    @Column(name = "domainCd")
    @ColumnDefault("1")
    private String domainCd;

    @Column(name = "userID", nullable = false)
    private String userId;

    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    @Column(name = "userPW", nullable = false)
    private String userPw;

    @Column(name = "userType",nullable = true)
    @ColumnDefault("1")
    private String userType;

    @Column(name = "userStatus",nullable = true)
    @ColumnDefault("1")
    private String userStatus;

    @Column(name = "userNm",nullable = false)
    private String userNm;

    @Column(name = "userPhoneNo",nullable = false)
    private String userPhoneNo;

    @Column(name = "userNickNm",nullable = true)
    private String userNickNm;

    @Column(name = "userGender",nullable = true)
    private String userGender;

    @Column(name = "userBirth",nullable = true)
    private String userBirth;

    @OneToMany(mappedBy = "userEntity", fetch = FetchType.EAGER, cascade = CascadeType.ALL)
    @Builder.Default
    private List<AuthEntity> roles = new ArrayList<>();

    public void setRoles(List<AuthEntity> role) {
        this.roles = role;
        role.forEach(o -> o.setUserEntity(this));
    }
}