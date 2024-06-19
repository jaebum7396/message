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

    @Column( name = "GOAL_PRICE_PERCENT")
    int goalPricePercent;

    @Column( name = "STOCK_SELECTION_COUNT")
    int stockSelectionCount;

    @Column( name = "QUOTE_ASSET_VOLUME_STANDARD")
    BigDecimal quoteAssetVolumeStandard; // 평균거래량 기준

    @Column( name = "AVERAGE_QUOTE_ASSET_VOLUME")
    BigDecimal averageQuoteAssetVolume; // 평균거래량

    @Column( name = "FLUCTUATION_RATE")
    BigDecimal fluctuationRate; // 변동률

    @Column( name = "COLLATERAL")
    BigDecimal collateral; // 할당된 담보금
    
    @Column( name = "COLLATERAL_RATE")
    BigDecimal collateralRate; // 할당된 담보금 비율
}