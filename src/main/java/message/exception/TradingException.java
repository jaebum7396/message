package message.exception;

import message.model.entity.MessageEntity;

public class TradingException extends RuntimeException{
    public TradingException(MessageEntity messageEntity) {
        super("TradingException");
    }
}
