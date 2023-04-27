package user.model;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.FetchType;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.JoinColumn;
import javax.persistence.ManyToOne;

import com.fasterxml.jackson.annotation.JsonIgnore;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;


@AllArgsConstructor
@NoArgsConstructor
@Getter
@Builder
@EqualsAndHashCode(callSuper=false)
@Entity(name = "AUTH")
public class Auth extends BaseEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @JsonIgnore
    @Column(name = "AUTH_CD")
    private Long authCd;

    @Column(name = "AUTH_TYPE")
    private String authType;

    @JoinColumn(name = "userCd")
    @ManyToOne(fetch = FetchType.LAZY)
    @JsonIgnore
    private User userEntity;
    public void setUser(User userEntity) {
        this.userEntity = userEntity;
    }
}