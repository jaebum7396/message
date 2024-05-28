package trade.configuration;

import com.binance.connector.futures.client.impl.UMWebsocketClientImpl;
import com.binance.connector.futures.client.utils.*;
import okhttp3.Request;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import trade.future.model.entity.TradingEntity;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

@Component
public class MyWebSocketClientImpl extends UMWebsocketClientImpl implements MyWebSocketClient {
    private final Map<Integer, WebSocketConnection> connections = new HashMap<>();
    private final Map<Integer, TradingEntity> TradingEntitys = new HashMap<>();
    private static final Logger logger = LoggerFactory.getLogger(MyWebSocketClientImpl.class);

    @Override
    public TradingEntity createConnection(
            TradingEntity tradingEntity,
            WebSocketCallback onOpenCallback,
            WebSocketCallback onMessageCallback,
            WebSocketCallback onClosingCallback,
            WebSocketCallback onFailureCallback,
            Request request
    ) {
        WebSocketConnection connection = new MyWebSocketConnection(tradingEntity, onOpenCallback, onMessageCallback, onClosingCallback, onFailureCallback, request);
        connection.connect();
        int connectionId = connection.getConnectionId();
        connections.put(connectionId, connection);
        tradingEntity.setStreamId(connectionId);
        TradingEntitys.put(connectionId, tradingEntity);
        return tradingEntity;
    }

    public TradingEntity getTradingEntity(int connectionId) {
        return TradingEntitys.get(connectionId);
    }

    @Override
    public void closeConnection(int connectionId) {
        if (connections.containsKey(connectionId)) {
            connections.get(connectionId).close();
            logger.info("Closing Connection ID {}", connectionId);
            connections.remove(connectionId);
        } else {
            logger.info("Connection ID {} does not exist!", connectionId);
        }
    }

    @Override
    public TradingEntity klineStream(TradingEntity tradingEntity, WebSocketCallback onOpenCallback, WebSocketCallback onMessageCallback, WebSocketCallback onClosingCallback, WebSocketCallback onFailureCallback) {
        ParameterChecker.checkParameterType(tradingEntity.getSymbol(), String.class, "symbol");
        Request request = RequestBuilder.buildWebsocketRequest(String.format("%s/ws/%s@kline_%s", super.getBaseUrl(), tradingEntity.getSymbol().toLowerCase(), tradingEntity.getCandleInterval()));
        return createConnection(tradingEntity, onOpenCallback, onMessageCallback, onClosingCallback, onFailureCallback, request);
    }

    @Override
    public TradingEntity combineStreams(TradingEntity tradingEntity, ArrayList<String> streams, WebSocketCallback onOpenCallback, WebSocketCallback onMessageCallback, WebSocketCallback onClosingCallback, WebSocketCallback onFailureCallback) {
        String url = UrlBuilder.buildStreamUrl(String.format("%s/stream", super.getBaseUrl()), streams);
        Request request = RequestBuilder.buildWebsocketRequest(url);
        return createConnection(tradingEntity, onOpenCallback, onMessageCallback, onClosingCallback, onFailureCallback, request);
    }
}
