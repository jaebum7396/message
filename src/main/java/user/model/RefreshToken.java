package user.model;

import org.springframework.data.redis.core.RedisHash;

import javax.persistence.Id;

@RedisHash(value = "refreshToken", timeToLive = 60)
public class RefreshToken {

    @Id
    private String refreshToken;
    private String userCd;

    public RefreshToken(String refreshToken, String userCd) {
        this.refreshToken = refreshToken;
        this.userCd = userCd;
    }

    public String getRefreshToken() {
        return refreshToken;
    }

    public String getUserCd() {
        return userCd;
    }
}