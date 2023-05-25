package user.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.*;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.DynamicInsert;
import org.hibernate.annotations.DynamicUpdate;
import org.hibernate.annotations.GenericGenerator;

import javax.persistence.*;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@AllArgsConstructor
@NoArgsConstructor
@EqualsAndHashCode(callSuper=false)
@Data
@Builder
@DynamicInsert
@DynamicUpdate
@Entity(name = "USER")
public class User extends BaseEntity {
    @Id @GeneratedValue(generator = "uuid2")
    @GenericGenerator(name = "uuid2", strategy = "uuid2")
    @Column(columnDefinition = "BINARY(16)", name = "USER_CD")
    private UUID userCd;

    @OneToOne(fetch = FetchType.EAGER, cascade = CascadeType.ALL) @JoinColumn(name = "USER_CD")
    private UserInfo userInfo;

    @OneToMany(mappedBy = "userEntity", fetch = FetchType.LAZY, cascade = CascadeType.ALL) @Builder.Default
    private List<Auth> roles = new ArrayList<>();

    public void setRoles(List<Auth> roles) {
        this.roles = new ArrayList<>(roles);
        roles.forEach(o -> o.setUser(this));
    }

    @Column(name = "DOMAIN_CD") @ColumnDefault("1")
    private String domainCd;

    @Column(name = "USER_ID", nullable = false)
    private String userId;

    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    @Column(name = "USER_PW", nullable = false)
    private String userPw;

    @Column(name = "USER_TYPE",nullable = true) @ColumnDefault("1")
    private String userType;

    @Column(name = "USER_STATUS",nullable = true) @ColumnDefault("1")
    private String userStatus;

    @Column(name = "USER_NM",nullable = false)
    private String userNm;

    @Column(name = "USER_PHONE_NUMBER",nullable = false)
    private String userPhoneNo;

    @Column(name = "USER_GENDER",nullable = true)
    private String userGender;

    @Column(name = "USER_BIRTH",nullable = true)
    private String userBirth;
}