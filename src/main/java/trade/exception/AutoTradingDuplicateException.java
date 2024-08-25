package trade.exception;

import lombok.extern.slf4j.Slf4j;
import trade.future.model.entity.TradingEntity;

@Slf4j
public class AutoTradingDuplicateException extends RuntimeException{
    public AutoTradingDuplicateException(String message) {
        //super(message);
        log.error("AutoTradingDuplicateException >>>>>" + message);
    }
}
