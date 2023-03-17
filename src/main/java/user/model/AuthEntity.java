package user.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.*;

import javax.persistence.*;

@AllArgsConstructor
@NoArgsConstructor
@Getter
@Builder
@EqualsAndHashCode(callSuper=false)
@Entity(name = "TB_AUTH")
public class AuthEntity extends BaseEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @JsonIgnore
    private Long authCd;

    private String authType;

    @JoinColumn(name = "userCd")
    @ManyToOne(fetch = FetchType.LAZY)
    @JsonIgnore
    private UserEntity userEntity;
    public void setUserEntity(UserEntity userEntity) {
        this.userEntity = userEntity;
    }
}