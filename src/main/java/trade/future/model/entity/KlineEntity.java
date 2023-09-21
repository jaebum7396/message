package trade.future.model.entity;

import lombok.*;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.GenericGenerator;
import trade.common.model.BaseEntity;

import javax.persistence.*;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper=false)
@Entity(name = "KLINE")
public class KlineEntity extends BaseEntity implements Serializable {
    @Id
    @GeneratedValue(generator = "uuid")
    @GenericGenerator(name = "uuid", strategy = "org.hibernate.id.UUIDGenerator")
    @Column( name = "KLINE_CD")
    private String kLineCd; // ID 필드 추가 (데이터베이스 식별자)
    /*@OneToOne(cascade = CascadeType.ALL) // CascadeType.ALL을 사용하여 관련된 KlineEntity도 저장 및 업데이트
    @JoinColumn(name = "KLINE_EVENT_CD") // KlineEntity와의 관계를 설정하는 외래 키
    private KlineEventEntity klineEvent;*/
    @Column( name = "START_TIME")
    private LocalDateTime startTime; // Kline 시작 시간
    @Column( name = "END_TIME")
    private LocalDateTime endTime; // Kline 종료 시간
    @Column( name = "SYMBOL")
    private String symbol; // 심볼
    @Column( name = "CANDLE_INTERVAL")
    private String candleInterval; // 인터벌
    @Column( name = "FIRST_TRADE_ID")
    private BigDecimal firstTradeId; // 첫 번째 거래 ID
    @Column( name = "LAST_TRADE_ID")
    private BigDecimal lastTradeId; // 마지막 거래 ID
    @Column( name = "OPEN_PRICE")
    private BigDecimal openPrice; // 시가 (Open)
    @Column( name = "CLOSE_PRICE")
    private BigDecimal closePrice; // 종가 (Close)
    @Column( name = "HIGH_PRICE")
    private BigDecimal highPrice; // 고가 (High)
    @Column( name = "LOW_PRICE")
    private BigDecimal lowPrice; // 저가 (Low)
    @Column( name = "VOLUME")
    private BigDecimal volume; // 거래량 (Volume)
    @Column( name = "TRADE_COUNT")
    private int tradeCount; // 거래 횟수
    @Column( name = "IS_CLOSED")
    private boolean isClosed; // 종료 여부
    @Column( name = "QUOTE_ASSET_VOLUME")
    private BigDecimal quoteAssetVolume; // 종료 시기의 가중 평균 가격 (Quote Asset Volume)
    @Column( name = "TAKER_BUY_BASE_ASSET_VOLUME")
    private BigDecimal takerBuyBaseAssetVolume; // 종료 시기의 거래량 (Taker Buy Base Asset Volume)
    @Column( name = "TAKER_BUY_QUOTE_ASSET_VOLUME")
    private BigDecimal takerBuyQuoteAssetVolume; // 종료 시기의 거래량 (Taker Buy Quote Asset Volume)
    @Column( name = "GOAL_PRICE_PLUS")
    @Builder.Default
    private boolean goalPricePlus = false; //상방으로 목표가 달성
    @Column( name = "GOAL_PRICE_MINUS")
    @Builder.Default
    private boolean goalPriceMinus = false; //하방으로 목표가 달성
    @Column( name = "IGNORE_FIELD")
    private int ignoreField;
}