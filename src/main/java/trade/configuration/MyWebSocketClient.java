package trade.configuration;

import com.binance.connector.futures.client.WebsocketClient;
import com.binance.connector.futures.client.utils.WebSocketCallback;
import okhttp3.Request;
import trade.future.model.entity.TradingEntity;

import java.util.ArrayList;

public interface MyWebSocketClient extends WebsocketClient {
    public TradingEntity createConnection(
            TradingEntity tradingEntity,
            WebSocketCallback onOpenCallback,
            WebSocketCallback onMessageCallback,
            WebSocketCallback onClosingCallback,
            WebSocketCallback onFailureCallback,
            Request request
    );
    TradingEntity klineStream(TradingEntity tradingEntity, WebSocketCallback onOpenCallback, WebSocketCallback onMessageCallback, WebSocketCallback onClosingCallback, WebSocketCallback onFailureCallback);

    TradingEntity combineStreams(TradingEntity tradingEntity, ArrayList<String> streams, WebSocketCallback onOpenCallback, WebSocketCallback onMessageCallback, WebSocketCallback onClosingCallback, WebSocketCallback onFailureCallback);
}