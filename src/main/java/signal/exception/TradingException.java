package signal.exception;

import signal.broadcast.model.entity.BroadCastEntity;

public class TradingException extends RuntimeException{
    public TradingException(BroadCastEntity broadCastEntity) {
        super("TradingException");
    }
}
