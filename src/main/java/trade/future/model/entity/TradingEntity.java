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

    @OneToOne(fetch = FetchType.LAZY, cascade = CascadeType.ALL)
    @JoinColumn(name = "POSITION_CD")
    private PositionEntity positionEntity;

    @Column( name = "SYMBOL")
    private String symbol; // 포지션 사이드

    @Column( name = "TRADING_STATUS")
    private String tradingStatus; // 포지션 상태

    @Column( name = "STREAM_ID")
    private int streamId; // 포지션 상태

    @Column( name = "CANDLE_INTERVAL")
    String candleInterval; // 캔들 기준

    @Column( name = "LEVERAGE")
    int leverage;

    @Column( name = "GOAL_PRICE_PERCENT")
    int goalPricePercent;

    @Column( name = "QUOTE_ASSET_VOLUME_STANDARD")
    BigDecimal quoteAssetVolumeStandard; // 평균거래량 기준

    @Column( name = "AVERAGE_QUOTE_ASSET_VOLUME")
    BigDecimal averageQuoteAssetVolume; // 평균거래량
}