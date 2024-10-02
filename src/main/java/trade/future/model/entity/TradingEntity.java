package trade.future.model.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.Comment;
import org.hibernate.annotations.GenericGenerator;
import trade.common.model.BaseEntity;
import trade.future.model.dto.TradingDTO;

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
public class TradingEntity extends BaseEntity implements Serializable, Cloneable {
    @Id
    @GeneratedValue(generator = "uuid")
    @GenericGenerator(name = "uuid", strategy = "org.hibernate.id.UUIDGenerator")
    @Column( name = "TRADING_CD")
    private String tradingCd; // ID 필드 추가 (데이터베이스 식별자)

    @Column( name = "USER_CD")
    @Comment("유저 식별자")
    private String userCd; // 유저 식별자

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

    @Column( name = "OPEN_PRICE", precision = 19, scale = 8)
    private BigDecimal openPrice; // 진입가격

    @Column( name = "CLOSE_PRICE", precision = 19, scale = 8)
    private BigDecimal closePrice;  // 청산가격

    @Column ( name = "PROFIT", precision = 19, scale = 8)
    private BigDecimal profit; // 수익률

    @Column( name = "STREAM_ID")
    private int streamId; // 스트림 ID

    @Column( name = "STOCK_SELECTION_COUNT")
    int stockSelectionCount;

    @Column( name = "MAX_POSITION_COUNT")
    int maxPositionCount;

    @Column( name = "SHORT_MOVING_PERIOD")
    int shortMovingPeriod;

    @Column( name = "MID_MOVING_PERIOD")
    int midMovingPeriod;

    @Column( name = "LONG_MOVING_PERIOD")
    int longMovingPeriod;

    @Column( name = "COLLATERAL", precision = 19, scale = 8)
    BigDecimal collateral; // 할당된 담보금
    
    @Column( name = "COLLATERAL_RATE", precision = 19, scale = 8)
    BigDecimal collateralRate; // 할당된 담보금 비율

    @Column( name = "STOP_LOSS_CHECKER")
    int stopLossChecker; // 손절 매매전략 수립

    @Column( name = "STOP_LOSS_RATE")
    int stopLossRate; // 손절 비율

    @Column( name = "TAKE_PROFIT_CHECKER")
    int takeProfitChecker; // 익절 매매전략 수립

    @Column( name = "TAKE_PROFIT_RATE")
    int takeProfitRate; // 익절 비율

    @Column( name = "PRICE_CHANGE_THRESHOLD")
    double priceChangeThreshold; // 가격 변동 임계값

    @Column( name = "TREND_5m")
    private String trend5m; // 5분 트렌드

    @Column( name = "TREND_15m")
    private String trend15m; // 15분 트렌드

    @Column( name = "TREND_1h")
    private String trend1h; // 1시간 트렌드

    @Column( name = "TREND_4h")
    private String trend4h; // 4시간 트렌드

    @Column( name = "ADX_5m")
    private String adx5m; // 5분 ADX

    @Column( name = "ADX_15m")
    private String adx15m; // 15분 ADX

    @Column( name = "ADX_1h")
    private String adx1h; // 1시간 ADX

    @Column( name = "ADX_4h")
    private String adx4h; // 4시간 ADX

    // 머신러닝이 주는 신호
    @Column( name = "SIGNAL_1m")
    private String signal1m; // 5분 신호

    int entryCount; // 최대 진입 횟수


    public TradingDTO toDTO() {
        return TradingDTO.builder()
                .leverage(this.leverage)
                .stockSelectionCount(this.stockSelectionCount)
                .maxPositionCount(this.maxPositionCount)
                .symbol(this.symbol)
                .targetSymbol(this.targetSymbol)
                .collateralRate(this.collateralRate)
                .build();
    }

    // 복사 생성자
    public TradingEntity(TradingEntity original) {
        this.tradingCd = original.tradingCd;
        this.userCd = original.userCd;
        this.tradingStatus = original.tradingStatus;
        this.positionStatus = original.positionStatus;
        this.symbol = original.symbol;
        this.targetSymbol = original.targetSymbol;
        this.leverage = original.leverage;
        this.positionSide = original.positionSide;
        this.openPrice = original.openPrice != null ? new BigDecimal(original.openPrice.toString()) : null;
        this.closePrice = original.closePrice != null ? new BigDecimal(original.closePrice.toString()) : null;
        this.profit = original.profit != null ? new BigDecimal(original.profit.toString()) : null;
        this.streamId = original.streamId;
        this.stockSelectionCount = original.stockSelectionCount;
        this.maxPositionCount = original.maxPositionCount;
        this.collateral = original.collateral != null ? new BigDecimal(original.collateral.toString()) : null;
        this.collateralRate = original.collateralRate != null ? new BigDecimal(original.collateralRate.toString()) : null;

        this.stopLossChecker = original.stopLossChecker;
        this.stopLossRate = original.stopLossRate;
        this.takeProfitChecker = original.takeProfitChecker;
        this.takeProfitRate = original.takeProfitRate;

        this.shortMovingPeriod = original.shortMovingPeriod;
        this.midMovingPeriod = original.midMovingPeriod;
        this.longMovingPeriod = original.longMovingPeriod;

        this.priceChangeThreshold = original.priceChangeThreshold;
    }

    @Override
    public TradingEntity clone() {
        return new TradingEntity(this);
    }
}