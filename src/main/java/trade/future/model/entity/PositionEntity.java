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

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "KLINE_CD")
    private KlineEntity klineEntity;

    @Column( name = "POSITION_SIDE")
    private String positionSide; // 포지션 사이드

    @Column( name = "POSITION_STATUS")
    private String positionStatus; // 포지션 상태

    @Column( name = "GOAL_PRICE_CHECK")
    @Builder.Default
    private boolean goalPriceCheck = false; // 목표가 도달 여부

    @Column( name = "GOAL_PRICE_PLUS")
    @Builder.Default
    private boolean goalPricePlus = false; //상방으로 목표가 달성

    @Column( name = "GOAL_PRICE_MINUS")
    @Builder.Default
    private boolean goalPriceMinus = false; //하방으로 목표가 달성

    @Column( name = "PLUS_GOAL_PRICE", precision = 19, scale = 8)
    private BigDecimal plusGoalPrice;

    @Column( name = "MINUS_GOAL_PRICE", precision = 19, scale = 8)
    private BigDecimal minusGoalPrice;

    @Column( name = "GOAL_PRICE_PERCENT", precision = 19, scale = 8)
    private int goalPricePercent;

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