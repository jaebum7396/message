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
@Entity(name = "USER")
public class User extends BaseEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "USER_CD")
    private Long userCd;

    @Column(name = "DOMAIN_CD")
    @ColumnDefault("1")
    private String domainCd;

    @Column(name = "USER_ID", nullable = false)
    private String userId;

    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    @Column(name = "USER_PW", nullable = false)
    private String userPw;

    @Column(name = "USER_TYPE",nullable = true)
    @ColumnDefault("1")
    private String userType;

    @Column(name = "USER_STATUS",nullable = true)
    @ColumnDefault("1")
    private String userStatus;

    @Column(name = "USER_NM",nullable = false)
    private String userNm;

    @Column(name = "USER_PHONE_NUMBER",nullable = false)
    private String userPhoneNo;

    @Column(name = "USER_NICK_NM",nullable = true)
    private String userNickNm;

    @Column(name = "USER_GENDER",nullable = true)
    private String userGender;

    @Column(name = "USER_BIRTH",nullable = true)
    private String userBirth;

    @OneToMany(mappedBy = "userEntity", fetch = FetchType.EAGER, cascade = CascadeType.ALL)
    @Builder.Default
    private List<Auth> roles = new ArrayList<>();

    public void setRoles(List<Auth> role) {
        this.roles = role;
        role.forEach(o -> o.setUser(this));
    }
}