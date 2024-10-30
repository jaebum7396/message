package message.exception;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class MessageException extends RuntimeException{
    public MessageException(String message) {
        //super(message);
        log.error("AutoTradingDuplicateException >>>>>" + message);
    }
}
