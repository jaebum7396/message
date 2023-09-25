package trade.future.model.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.*;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.GenericGenerator;
import trade.common.model.BaseEntity;

import javax.persistence.*;
import java.io.Serializable;
import java.math.BigDecimal;

@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper=false)
@Entity(name = "POSITION")
public class PositionEntity extends BaseEntity implements Serializable {
    @Id
    @GeneratedValue(generator = "uuid")
    @GenericGenerator(name = "uuid", strategy = "org.hibernate.id.UUIDGenerator")
    @Column( name = "POSITION_CD")
    private String positionCd; // ID 필드 추가 (데이터베이스 식별자)

    @OneToOne(fetch = FetchType.LAZY) // CascadeType.ALL을 사용하여 관련된 KlineEntity도 저장 및 업데이트
    @JoinColumn(name = "KLINE_CD") // KlineEntity와의 관계를 설정하는 외래 키
    @JsonIgnore
    private KlineEntity kline;

    @Column( name = "POSITION_SIDE")
    private String positionSide; // 포지션 사이드

    @Column( name = "POSITION_STATUS")
    private String positionStatus; // 포지션 상태

    @Column( name = "REALIZATION_PNL")
    private BigDecimal realizatioPnl; // 실현손익

    @Override
    public String toString() {
        return "PositionEntity{" +
                "positionCd='" + positionCd + '\'' +
                ", positionSide='" + positionSide + '\'' +
                ", positionStatus='" + positionStatus + '\'' +
                ", realizatioPnl=" + realizatioPnl +
                '}';
    }
}