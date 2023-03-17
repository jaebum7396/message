package user.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Getter;
import org.hibernate.annotations.ColumnDefault;
import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedBy;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import javax.persistence.Column;
import javax.persistence.EntityListeners;
import javax.persistence.MappedSuperclass;
import java.time.LocalDateTime;

@EntityListeners(AuditingEntityListener.class)
@Getter
@MappedSuperclass
public abstract class BaseEntity {
    @CreatedDate
    @Column(nullable = false, updatable = false)
    @JsonIgnore
    private LocalDateTime insertDt;

    @CreatedBy
    @JsonIgnore
    private Long insertUserCd;

    @LastModifiedDate
    @JsonIgnore
    private LocalDateTime updateDt;

    @LastModifiedBy
    @JsonIgnore
    private Long updateUserCd;

    @Column(name = "deleteYn")
    @ColumnDefault("-1")
    @JsonIgnore
    private String deleteYn;

    @JsonIgnore
    private LocalDateTime deleteDt;

    @JsonIgnore
    private Long deleteUserCd;

    @JsonIgnore
    private String remark;
}
