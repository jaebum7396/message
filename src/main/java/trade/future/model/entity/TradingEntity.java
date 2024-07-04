package trade.future.model.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.GenericGenerator;
import trade.common.model.BaseEntity;

import javax.persistence.*;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.List;

@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper=false)
@Entity(name = "TRADING")
public class TradingEntity extends BaseEntity implements Serializable {
    @Id
    @GeneratedValue(generator = "uuid")
    @GenericGenerator(name = "uuid", strategy = "org.hibernate.id.UUIDGenerator")
    @Column( name = "TRADING_CD")
    private String tradingCd; // ID 필드 추가 (데이터베이스 식별자)

    @Column( name = "USER_CD")
    private String userCd; // 유저 식별자

    @Column( name = "TRADING_TYPE")
    private String tradingType; // 트레이딩 타입(실제, 백테스트)

    @Column( name = "TRADING_STATUS")
    private String tradingStatus; // 포지션 상태

    @Column( name = "POSITION_STATUS")
    private String positionStatus; // 포지션 상태

    @Column( name = "SYMBOL")
    private String symbol; // 거래 페어

    @Column( name = "TARGET_SYMBOL")
    private String targetSymbol; // 타게팅된 심볼

    @Column( name = "LEVERAGE")
    int leverage; // 레버리지

    @Column( name = "POSITION_SIDE")
    private String positionSide; // 포지션 사이드

    @Column( name = "OPEN_PRICE")
    private BigDecimal openPrice; // 진입가격

    @Column( name = "CLOSE_PRICE")
    private BigDecimal closePrice;  // 청산가격

    @Column ( name = "PROFIT")
    private BigDecimal profit; // 수익률

    @Column( name = "STREAM_ID")
    private int streamId; // 스트림 ID

    @Column( name = "CANDLE_INTERVAL")
    String candleInterval; // 캔들 기준

    @Column( name = "STOCK_SELECTION_COUNT")
    int stockSelectionCount;

    @Column( name = "MAX_POSITION_COUNT")
    int maxPositionCount;

    @Column( name = "COLLATERAL")
    BigDecimal collateral; // 할당된 담보금
    
    @Column( name = "COLLATERAL_RATE")
    BigDecimal collateralRate; // 할당된 담보금 비율

    @Column( name = "ADX_CHECKER")
    boolean adxChecker; //ADX로 매매전략 수립

    @Column( name = "MACD_HISTOGRAM_CHECKER")
    boolean macdHistogramChecker; //MACD 히스토그램으로 매매전략 수립
}