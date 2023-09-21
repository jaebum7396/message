package trade.future.model.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.NoArgsConstructor;
import lombok.ToString;
import trade.common.CommonUtils;
import trade.future.model.entity.KlineEntity;

import java.math.BigDecimal;

@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
@ToString
public class KlineDTO {
    private long t; // Kline 시작 시간
    private long T; // Kline 종료 시간
    private String s; // 심볼
    private String i; // 인터벌
    private BigDecimal f; // 첫 번째 거래 ID
    private BigDecimal L; // 마지막 거래 ID
    private BigDecimal o; // 시가 (Open)
    private BigDecimal c; // 종가 (Close)
    private BigDecimal h; // 고가 (High)
    private BigDecimal l; // 저가 (Low)
    private BigDecimal v; // 거래량 (Volume)
    private int n; // 거래 횟수
    private boolean x; // 종료 여부
    private BigDecimal q; // 종료 시기의 가중 평균 가격 (Quote Asset Volume)
    private BigDecimal V; // 종료 시기의 거래량 (Taker Buy Base Asset Volume)
    private BigDecimal Q; // 종료 시기의 거래량 (Taker Buy Quote Asset Volume)
    private int B; // 무시 필드

    public KlineEntity toEntity() {
        KlineEntity entity = new KlineEntity();
        entity.setStartTime(CommonUtils.convertTimestampToDateTime(this.T));
        entity.setEndTime(CommonUtils.convertTimestampToDateTime(this.T));
        entity.setSymbol(this.s);
        entity.setCandleInterval(this.i);
        entity.setFirstTradeId(this.f);
        entity.setLastTradeId(this.L);
        entity.setOpenPrice(this.o);
        entity.setClosePrice(this.c);
        entity.setHighPrice(this.h);
        entity.setLowPrice(this.l);
        entity.setVolume(this.v);
        entity.setTradeCount(this.n);
        entity.setClosed(this.x);
        entity.setQuoteAssetVolume(this.q);
        entity.setTakerBuyBaseAssetVolume(this.V);
        entity.setTakerBuyQuoteAssetVolume(this.Q);
        entity.setIgnoreField(this.B);
        return entity;
    }
}
