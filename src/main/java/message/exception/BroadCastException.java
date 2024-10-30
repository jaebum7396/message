package message.exception;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class BroadCastException extends RuntimeException{
    public BroadCastException(String message) {
        //super(message);
        log.error("AutoTradingDuplicateException >>>>>" + message);
    }
}
