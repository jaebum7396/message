package user.model;

import io.swagger.annotations.ApiModelProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class SignupRequest {
    @ApiModelProperty(value="userId", example="gildongh0366", required=true)
    private String userId;
    @ApiModelProperty(value="userPw", example="pwd0366", required=true)
    private String userPw;
    @ApiModelProperty(value="userNm", example="홍길동", required=true)
    private String userNm;
    @ApiModelProperty(value="userPhoneNo", example="010-0000-0366", required=true)
    private String userPhoneNo;

    public UserEntity toEntity() {
        return UserEntity.builder()
                .userId(userId)
                .userPw(userPw)
                .userNm(userNm)
                .userPhoneNo(userPhoneNo)
                .build();
    }
}