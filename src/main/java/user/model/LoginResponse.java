package user.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Getter
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class LoginResponse {
    public LoginResponse(UserEntity userEntity) {
        this.userId = userEntity.getUserId();
        this.userNm = userEntity.getUserNm();
        this.roles = userEntity.getRoles();
    }
    private String userId;
    private String userNm;
    @Builder.Default
    private List<AuthEntity> roles = new ArrayList<AuthEntity>();
    private String token;
}