package trade.future.model.entity;

import lombok.Data;
import lombok.ToString;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@ToString
public class AggTradeEventEntity {
    // 이벤트 유형
    private String eventType;
    // 체결 ID
    private long tradeId;
    // 이벤트 시간
    private LocalDateTime eventTime;
    // 심볼
    private String symbol;
    // 가격
    private BigDecimal price;
    // 수량
    private BigDecimal quantity;
    // 거래량
    private BigDecimal amount;
    // First trade ID
    private long firstTradeID;
    // Last trade ID
    private long lastTradeID;
    // 거래 시간
    private LocalDateTime tradeTime;
    // Is the buyer the market maker?
    private boolean marketMaker;
}
