package trade.future.model.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import lombok.Data;
import trade.common.CommonUtils;
import trade.common.LongDeserializer;
import trade.future.model.entity.AggTradeEventEntity;

import java.math.BigDecimal;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class AggTradeEventDTO {
    // 이벤트 유형
    private String e;
    // 체결 ID
    @JsonDeserialize(using = LongDeserializer.class)
    // 이벤트 시간
    private long E;
    // 심볼
    private String s;
    // Aggregate trade ID
    private long a;
    // 가격
    private String p;
    // 수량
    private String q;
    // 매수자 주문 ID
    private long f;
    // 매도자 주문 ID
    private long l;
    // 거래 시간
    @JsonDeserialize(using = LongDeserializer.class)
    private long T;
    // Is the buyer the market maker?
    private boolean m;

    @Override
    public String toString() {
        return "AggTradeEventEntity{" +
            "e='" + e + '\'' +
            ", E=" + E +
            ", s='" + s + '\'' +
            ", a=" + a +
            ", p='" + p + '\'' +
            ", q='" + q + '\'' +
            ", f=" + f +
            ", l=" + l +
            ", T=" + T +
            ", m=" + m +
        '}';
    }

    public AggTradeEventEntity toEntity() {
        AggTradeEventEntity entity = new AggTradeEventEntity();
        entity.setEventType(this.e);
        entity.setEventTime(CommonUtils.convertTimestampToDateTime(this.E));
        entity.setSymbol(this.s);
        entity.setTradeId(this.a);
        entity.setPrice(new BigDecimal(this.p));
        entity.setQuantity(new BigDecimal(this.q));
        entity.setAmount(entity.getPrice().multiply(entity.getQuantity()));
        entity.setFirstTradeID(this.f);
        entity.setLastTradeID(this.l);
        entity.setTradeTime(CommonUtils.convertTimestampToDateTime(this.T));
        entity.setMarketMaker(this.m);
        return entity;
    }
}
