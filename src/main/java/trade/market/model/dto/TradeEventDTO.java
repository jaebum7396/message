package trade.market.model.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import lombok.Data;
import trade.common.CommonUtils;
import trade.common.LongDeserializer;
import trade.market.model.entity.TradeEventEntity;

import java.math.BigDecimal;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class TradeEventDTO {
    // 이벤트 유형
    private String e;
    // 체결 ID
    private long t;
    @JsonDeserialize(using = LongDeserializer.class)
    // 이벤트 시간
    private long E;
    // 심볼
    private String s;
    // 가격
    private String p;
    // 수량
    private String q;
    // 매수자 주문 ID
    private long b;
    // 매도자 주문 ID
    private long a;
    // 거래 시간
    private long T;
    // Is the buyer the market maker?
    private boolean m;
    // Ignore
    private boolean M;

    @Override
    public String toString() {
        return "TradeEventDTO{" +
            "e='" + e + '\'' +
            ", E=" + E +
            ", s='" + s + '\'' +
            ", t=" + t +
            ", p='" + p + '\'' +
            ", q='" + q + '\'' +
            ", b=" + b +
            ", a=" + a +
            ", T=" + T +
            ", m=" + m +
            ", M=" + M +
        '}';
    }

    public TradeEventEntity toEntity() {
        TradeEventEntity entity = new TradeEventEntity();
        entity.setEventType(this.e);
        entity.setTradeId(this.t);
        entity.setEventTime(CommonUtils.convertTimestampToDateTime(this.E));
        entity.setSymbol(this.s);
        entity.setPrice(new BigDecimal(this.p));
        entity.setQuantity(new BigDecimal(this.q));
        entity.setAmount(entity.getPrice().multiply(entity.getQuantity()));
        entity.setBuyerOrderId(this.b);
        entity.setSellerOrderId(this.a);
        entity.setTradeTime(CommonUtils.convertTimestampToDateTime(this.T));
        entity.setMarketMaker(this.m);
        entity.setIgnore(this.M);
        return entity;
    }
}
