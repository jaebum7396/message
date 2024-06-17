package trade.exception;

import trade.future.model.entity.TradingEntity;

public class TradingException extends RuntimeException{
    public TradingException(TradingEntity tradingEntity) {
        super("TradingException");
    }
}
