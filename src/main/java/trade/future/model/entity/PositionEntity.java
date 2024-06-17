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

    @Column( name = "POSITION_TYPE")
    private String positionType;

    @Column( name = "SYMBOL")
    private String symbol;

    @Column( name = "LEVERAGE")
    private String leverage;

    @Column( name = "MAX_NOTIONAL")
    private BigDecimal maxNotional; // 최대 명목 가치입니다. 최대 포지션 크기 제한을 나타냅니다.

    @Column( name = "MAINT_MARGIN")
    private BigDecimal maintMargin; // 유지 마진입니다. 포지션을 유지하는 데 필요한 최소 마진을 의미합니다.

    @Column( name = "OPEN_ORDER_INITIAL_MARGIN")
    private BigDecimal openOrderInitialMargin; // 현재 열려있는 주문에 대한 초기 마진입니다.

    @Column( name = "NOTIONAL")
    private BigDecimal notional; // 포지션의 명목 가치입니다. 현재 포지션의 크기를 나타냅니다.

    @Column( name = "ISOLATED_WALLET")
    private BigDecimal isolatedWallet; // 격리된 마진 지갑의 잔액입니다. 격리 마진 모드에서는 각 포지션에 별도의 마진이 사용됩니다.

    @Column( name = "BREAK_EVEN_PRICE")
    private BigDecimal breakEvenPrice; // 손익분기점 가격입니다. 포지션의 수익이 0이 되는 가격입니다.

    @Column( name = "ASK_NOTIONAL")
    private BigDecimal askNotional; // 매수 명목 가치입니다. 매수 주문의 명목 가치를 나타냅니다.

    @Column( name = "POSITION_INITIAL_MARGIN")
    private BigDecimal positionInitialMargin; // 포지션에 대한 초기 마진입니다.

    @Column( name = "POSITION_SIDE")
    private String positionSide; // 포지션 사이드

    @Column( name = "ISOLATED")
    private boolean isolated; // 격리 여부입니다. 격리 마진 모드에서는 각 포지션에 별도의 마진이 사용됩니다.

    @Column( name = "INITIAL_MARGIN")
    private BigDecimal initialMargin; // 초기 마진입니다. 포지션을 열기 위해 필요한 최소 마진을 의미합니다.

    @Column( name = "ENTRY_PRICE")
    private BigDecimal entryPrice;

    @Column( name = "CLOSE_PRICE")
    private BigDecimal closePrice;

    @Column( name = "POSITION_AMT")
    private BigDecimal positionAmt; // 포지션 수량입니다.

    @Column( name = "PROFIT")
    private BigDecimal profit; // 수익입니다. 포지션의 수익을 나타냅니다.

    @Column( name = "BID_NOTIONAL")
    private BigDecimal bidNotional; // 매도 명목 가치입니다. 매도 주문의 명목 가치를 나타냅니다.

    @Column( name = "POSITION_STATUS")
    private String positionStatus; // 포지션 상태

    @Column( name = "GOAL_PRICE_CHECK")
    @Builder.Default
    private boolean goalPriceCheck = false; // 목표가 도달 여부

    @Column( name = "PLUS_GOAL_PRICE", precision = 19, scale = 8)
    private BigDecimal plusGoalPrice;

    @Column( name = "MINUS_GOAL_PRICE", precision = 19, scale = 8)
    private BigDecimal minusGoalPrice;

    @Column( name = "GOAL_PRICE_PERCENT", precision = 19, scale = 8)
    private int goalPricePercent;

    @Column( name = "REALIZATION_PNL")
    private BigDecimal realizatioPnl; // 실현손익

    @Column( name = "REMARK")
    private String remark; //

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